package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.AuthAuditLog;
import com.omniretail.backend.auth.entity.PasswordResetChallenge;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import com.omniretail.backend.auth.repository.PasswordResetChallengeRepository;
import com.omniretail.backend.auth.service.Totp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bloqueo escalonado (auth-policy.ts y registerFailedAuthAttempt de MockAuthRepository). El reloj de auth
 * ({@code authClock}) se reemplaza por uno que el test adelanta a mano: ventanas y niveles se prueban sin
 * esperar. Entre acciones se avanza al menos un segundo para que ningun evento comparta instante.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LoginLockoutEscalationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "Correcta12345!";
    private static final String WRONG = "Incorrecta12345!";
    private static final MutableClock CLOCK = new MutableClock();

    @TestBean(name = "authClock")
    private Clock authClock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private AuthAuditLogRepository auditLogRepository;

    @Autowired
    private PasswordResetChallengeRepository resetChallengeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static Clock authClock() {
        return CLOCK;
    }

    @BeforeEach
    void resetClock() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // --- Ventana y primer bloqueo ---

    @Test
    void fiveFailuresWithinTenMinutesLockFifteenMinutes() throws Exception {
        User user = employee();

        for (int attempt = 1; attempt <= 4; attempt++) {
            failLogin(user);
            assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);
            advance(Duration.ofMinutes(2));
        }
        failLogin(user);

        assertLockedFor(user, Duration.ofMinutes(15));
        assertThat(account(user).getFailedLoginAttempts()).isEqualTo(5);
        assertThat(actions(user)).containsExactly(
                "login_failed", "login_failed", "login_failed", "login_failed", "account_locked");
    }

    @Test
    void lockedAccountAnswersTheSameWithRightOrWrongPasswordAndDoesNotCount() throws Exception {
        User user = employee();
        lock(user);
        List<String> before = actions(user);
        Instant lockedUntil = account(user).getLockedUntil();

        advance(Duration.ofMinutes(1));
        String rightPassword = login(user, PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String wrongPassword = login(user, WRONG).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nadie-" + UUID.randomUUID() + "@test.local", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutTimestamp(rightPassword))
                .isEqualTo(withoutTimestamp(wrongPassword))
                .isEqualTo(withoutTimestamp(unknownEmail));
        // Los intentos con la cuenta bloqueada no se registran ni alargan el bloqueo.
        assertThat(actions(user)).isEqualTo(before);
        assertThat(account(user).getLockedUntil()).isEqualTo(lockedUntil);
    }

    @Test
    void theWindowSlidesFailuresOlderThanTenMinutesStopCounting() throws Exception {
        User user = employee();
        for (int minute : new int[] {0, 3, 6, 9}) {
            CLOCK.set(base().plus(Duration.ofMinutes(minute)));
            failLogin(user);
        }

        // Minuto 10:30: el fallo del minuto 0 ya salio de la ventana; este es el 4.o de la racha.
        CLOCK.set(base().plus(Duration.ofSeconds(630)));
        failLogin(user);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);

        // Minuto 10:40: 3, 6, 9 y 10:30 siguen en la ventana; este es el 5.o.
        CLOCK.set(base().plus(Duration.ofSeconds(640)));
        failLogin(user);
        assertLockedFor(user, Duration.ofMinutes(15));
    }

    // --- Escala ---

    @Test
    void lockoutsEscalateFifteenThirtySixtyAndStayAtSixty() throws Exception {
        User user = employee();

        lock(user);
        assertLockedFor(user, Duration.ofMinutes(15));

        advanceToUnlock(user);
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(30));

        advanceToUnlock(user);
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(60));

        // Justo al vencer el de 60 min el hueco es de 60 min exactos, no "mas de 60": no se reinicia.
        advanceToUnlock(user);
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(60));
    }

    @Test
    void moreThanSixtyQuietMinutesResetTheEscalation() throws Exception {
        User user = employee();
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(15));

        advance(Duration.ofMinutes(76));
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(15));
    }

    @Test
    void onlyLockoutsOfTheLastTwentyFourHoursRaiseTheLevel() throws Exception {
        User old = employee();
        // Un bloqueo de hace 25 h no cuenta; el fallo de hace 30 min evita el reinicio por inactividad.
        history(old, "account_locked", Duration.ofHours(25));
        history(old, "login_failed", Duration.ofMinutes(30));
        lock(old);
        assertLockedFor(old, Duration.ofMinutes(15));

        User recent = employee();
        history(recent, "account_locked", Duration.ofHours(23));
        history(recent, "login_failed", Duration.ofMinutes(30));
        lock(recent);
        assertLockedFor(recent, Duration.ofMinutes(30));
    }

    // --- Login correcto ---

    @Test
    void successfulLoginCutsTheStreakButKeepsTheLevel() throws Exception {
        User user = employee();
        lock(user);
        advanceToUnlock(user);

        failTimes(user, 4);
        login(user, PASSWORD).andExpect(status().isOk());
        advance(Duration.ofSeconds(1));
        failTimes(user, 4);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);

        failLogin(user);
        // Segundo bloqueo en 24 h: 30 min aunque hubo un login correcto en medio.
        assertLockedFor(user, Duration.ofMinutes(30));
    }

    // --- Restablecer contrasena ---

    @Test
    void passwordResetUnlocksButKeepsTheLevel() throws Exception {
        User user = employee();
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(15));

        // Fuera de la ventana de 10 min: si no, los fallos previos seguirian en la racha (igual que el mock).
        advance(Duration.ofMinutes(11));
        resetPassword(user).andExpect(status().isOk());
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);

        advance(Duration.ofSeconds(1));
        failTimes(user, 4);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);
        failLogin(user);
        // El reset no borra el historial: es el segundo bloqueo en 24 h.
        assertLockedFor(user, Duration.ofMinutes(30));
    }

    // --- MFA ---

    @Test
    void passwordAndMfaCodeFailuresShareTheCounter() throws Exception {
        User user = employee();
        enableMfa(user);

        failTimes(user, 3);
        String challenge = challenge(user);
        failCode(challenge);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);
        failCode(challenge);

        assertLockedFor(user, Duration.ofMinutes(15));
        assertThat(actions(user)).endsWith("login_failed", "login_failed", "login_failed", "mfa_failed",
                "account_locked");
    }

    /**
     * Con la contrasena ya en su poder, un atacante no puede alternar "codigos malos y login de nuevo":
     * la contrasena correcta con MFA no es login_success y no corta la racha.
     */
    @Test
    void correctPasswordWithMfaDoesNotResetTheStreak() throws Exception {
        User user = employee();
        enableMfa(user);

        String first = challenge(user);
        for (int attempt = 1; attempt <= 3; attempt++) {
            failCode(first);
        }
        String second = challenge(user);
        failCode(second);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);
        failCode(second);

        assertLockedFor(user, Duration.ofMinutes(15));
        List<String> afterEnabling = actions(user).subList(actions(user).indexOf("mfa_enabled") + 1, actions(user).size());
        assertThat(afterEnabling).doesNotContain("login_success").last().isEqualTo("account_locked");
    }

    @Test
    void mfaVerifySuccessIsTheLoginSuccess() throws Exception {
        User user = employee();
        byte[] secret = enableMfa(user);

        failTimes(user, 4);
        String challenge = challenge(user);
        verifyCode(challenge, Totp.generate(secret, Totp.step(CLOCK.instant()) + 1)).andExpect(status().isOk());
        assertThat(actions(user)).last().isEqualTo("login_success");

        // La racha se corto: 4 fallos mas no bloquean.
        advance(Duration.ofSeconds(1));
        failTimes(user, 4);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);
    }

    // --- Datos existentes y concurrencia ---

    @Test
    void accountLockedBeforeThisChangeStaysLockedUntilItExpires() throws Exception {
        User user = employee();
        AuthAccount legacy = account(user);
        legacy.setStatus(AccountStatus.temporarily_locked);
        legacy.setFailedLoginAttempts(5);
        legacy.setLockedUntil(CLOCK.instant().plus(20, ChronoUnit.MINUTES));
        authAccountRepository.save(legacy);

        advance(Duration.ofMinutes(19));
        login(user, PASSWORD).andExpect(status().isUnauthorized());
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.temporarily_locked);

        advance(Duration.ofMinutes(2));
        login(user, PASSWORD).andExpect(status().isOk());
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);

        // Sin historial previo, su siguiente bloqueo es el primero.
        advance(Duration.ofSeconds(1));
        lock(user);
        assertLockedFor(user, Duration.ofMinutes(15));
    }

    @Test
    void simultaneousFailuresCannotSkipTheLock() throws Exception {
        for (int round = 0; round < 3; round++) {
            User user = employee();
            failTimes(user, 3);

            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<Integer>> results = List.of(1, 2).stream()
                        .map(ignored -> executor.submit(() -> {
                            start.await();
                            return login(user, WRONG).andReturn().getResponse().getStatus();
                        }))
                        .toList();
                start.countDown();
                for (Future<Integer> result : results) {
                    assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(401);
                }
            }

            assertLockedFor(user, Duration.ofMinutes(15));
            assertThat(actions(user)).filteredOn("account_locked"::equals).hasSize(1);
            assertThat(actions(user)).filteredOn("login_failed"::equals).hasSize(4);
        }
    }

    @Test
    void recentEventsQueryUsesTheAccountCreatedAtIndex() {
        Tenant tenant = tenant();
        // Volumen suficiente para que el planificador prefiera el indice a recorrer la tabla.
        jdbcTemplate.update("""
                INSERT INTO auth_audit_logs (tenant_id, auth_account_id, action, created_at)
                SELECT ?, md5((i % 500)::text)::uuid, 'login_failed', now() - (i || ' seconds')::interval
                FROM generate_series(1, 20000) AS i""", tenant.getId());
        jdbcTemplate.execute("ANALYZE auth_audit_logs");

        String plan = String.join("\n", jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM auth_audit_logs
                WHERE auth_account_id = md5('7')::uuid AND created_at > now() - interval '24 hours'
                  AND action IN ('login_failed', 'mfa_failed', 'account_locked', 'login_success')
                ORDER BY created_at, id""", String.class));

        assertThat(plan).contains("idx_auth_audit_logs_account_created");
    }

    // --- Utilidades ---

    private Instant base;

    private Instant base() {
        if (base == null) {
            base = CLOCK.instant();
        }
        return base;
    }

    private static void advance(Duration duration) {
        CLOCK.set(CLOCK.instant().plus(duration));
    }

    private void advanceToUnlock(User user) {
        CLOCK.set(account(user).getLockedUntil());
    }

    /** 5 contrasenas incorrectas seguidas, un segundo entre cada una. */
    private void lock(User user) throws Exception {
        failTimes(user, 5);
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.temporarily_locked);
    }

    private void failTimes(User user, int times) throws Exception {
        for (int attempt = 0; attempt < times; attempt++) {
            failLogin(user);
            advance(Duration.ofSeconds(1));
        }
    }

    private void failLogin(User user) throws Exception {
        login(user, WRONG)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private void assertLockedFor(User user, Duration duration) {
        AuthAccount account = account(user);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.temporarily_locked);
        Instant lockedAt = auditLogRepository.findByAuthAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .filter(event -> "account_locked".equals(event.getAction()))
                .map(AuthAuditLog::getCreatedAt)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(account.getLockedUntil()).isEqualTo(lockedAt.plus(duration));
    }

    private void history(User user, String action, Duration ago) {
        auditLogRepository.save(AuthAuditLog.builder()
                .tenantId(user.getTenantId())
                .actorUserId(user.getId())
                .authAccountId(account(user).getId())
                .action(action)
                .createdAt(CLOCK.instant().minus(ago))
                .build());
    }

    /** Activa el MFA con un login sin MFA (login_success) y devuelve el secreto. */
    private byte[] enableMfa(User user) throws Exception {
        String token = JsonPath.read(login(user, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
        String begin = mockMvc.perform(post("/api/v1/auth/mfa/enrollment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"totp\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        byte[] secret = Totp.base32Decode(JsonPath.read(begin, "$.secret"));
        mockMvc.perform(post("/api/v1/auth/mfa/enrollment/verify").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(Totp.generate(secret, Totp.step(CLOCK.instant())))))
                .andExpect(status().isOk());
        advance(Duration.ofSeconds(1));
        return secret;
    }

    private String challenge(User user) throws Exception {
        String body = login(user, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        advance(Duration.ofSeconds(1));
        return JsonPath.read(body, "$.challengeToken");
    }

    private void failCode(String challengeToken) throws Exception {
        verifyCode(challengeToken, "000000").andExpect(status().isUnauthorized());
        advance(Duration.ofSeconds(1));
    }

    private ResultActions verifyCode(String challengeToken, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\": \"%s\", \"code\": \"%s\"}".formatted(challengeToken, code)));
    }

    /** Restablece la contrasena con un desafio de recuperacion vigente creado aqui (el token viaja por correo). */
    private ResultActions resetPassword(User user) throws Exception {
        String token = "reset-" + UUID.randomUUID();
        resetChallengeRepository.save(PasswordResetChallenge.builder()
                .userId(user.getId())
                .tokenHash(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))))
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build());
        return mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\", \"newPassword\": \"%s\"}".formatted(token, PASSWORD)));
    }

    private ResultActions login(User user, String password) throws Exception {
        return login(user.getEmail(), password);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
    }

    private AuthAccount account(User user) {
        return authAccountRepository.findByUserId(user.getId()).orElseThrow();
    }

    private List<String> actions(User user) {
        return auditLogRepository.findByAuthAccountIdOrderByCreatedAtAsc(account(user).getId()).stream()
                .map(AuthAuditLog::getAction)
                .toList();
    }

    private Tenant tenant() {
        return tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private User employee() {
        Tenant tenant = tenant();
        String email = "lockout-" + UUID.randomUUID() + "@test.local";
        User user = User.builder().name("Usuario test").email(email).type(UserType.employee).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(AccountStatus.active)
                .build());
        return user;
    }

    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }

    /** Reloj que el test adelanta a mano. Compartido por todos los tests de la clase (se reinicia en cada uno). */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
