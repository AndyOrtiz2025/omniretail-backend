package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.omniretail.backend.auth.entity.MfaEnrollment;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import com.omniretail.backend.auth.repository.MfaEnrollmentRepository;
import com.omniretail.backend.auth.repository.MfaRecoveryCodeRepository;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.Totp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Verificacion en dos pasos (TOTP). Los codigos se calculan con {@link Totp} a partir del secreto que
 * devuelve la activacion. Cada test fija un paso base lejos del borde de los 30 s ({@link #safeStep()}) y
 * usa ese paso, el anterior y el siguiente: un mismo paso no sirve dos veces.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MfaControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String MFA = "/api/v1/auth/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String ENROLLMENT_VERIFY = MFA + "/enrollment/verify";
    private static final String VERIFY = MFA + "/verify";
    private static final String DISABLE = MFA + "/disable";
    private static final String PASSWORD_CHANGE = "/api/v1/auth/password/change";
    private static final String PASSWORD = "Correcta12345!";
    private static final String CODE_INVALID = "El código no es correcto. Inténtalo de nuevo.";
    private static final String CHALLENGE_UNAVAILABLE = "El código no es válido o venció. Vuelve a iniciar sesión.";
    private static final String RECOVERY_CODE_FORMAT = "[0-9A-F]{4}-[0-9A-F]{4}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MfaEnrollmentRepository enrollmentRepository;

    @Autowired
    private MfaRecoveryCodeRepository recoveryCodeRepository;

    @Autowired
    private AuthAuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- Activacion ---

    @Test
    void statusWithoutEnrollmentIsDisabledWithoutMethod() throws Exception {
        String token = sessionToken(employee());

        mockMvc.perform(get(MFA).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.method").doesNotExist());
    }

    @Test
    void fullFlowActivateThenLoginWithChallengeGivesSession() throws Exception {
        long step = safeStep();
        Person person = employee();
        String token = sessionToken(person);

        String begin = beginEnrollment(token, "{\"method\": \"totp\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("totp"))
                .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/OmniRetail%3A")))
                .andReturn().getResponse().getContentAsString();
        String base32 = JsonPath.read(begin, "$.secret");
        assertThat((String) JsonPath.read(begin, "$.otpauthUri")).contains("secret=" + base32, "issuer=OmniRetail");
        byte[] secret = Totp.base32Decode(base32);
        mockMvc.perform(get(MFA).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.method").value("totp"));

        // El secreto se guarda cifrado, nunca en claro.
        MfaEnrollment pending = enrollment(person);
        assertThat(pending.getSecretCiphertext()).isNotBlank().doesNotContain(base32);

        String codes = verifyEnrollment(token, code(secret, step - 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes", hasSize(8)))
                .andReturn().getResponse().getContentAsString();
        List<String> recoveryCodes = JsonPath.read(codes, "$.recoveryCodes");
        assertThat(recoveryCodes).doesNotHaveDuplicates().allMatch(code -> code.matches(RECOVERY_CODE_FORMAT));
        assertThat(recoveryCodeRepository.findByUserId(person.user().getId()))
                .hasSize(8)
                .noneMatch(stored -> recoveryCodes.contains(stored.getCodeHash()));
        mockMvc.perform(get(MFA).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.method").value("totp"));
        assertThat(actions(person)).containsExactly("mfa_enabled");

        int sessionsBefore = activeSessions(person);
        String challenge = login(person)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.method").value("totp"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // El paso 1 no crea sesion.
        assertThat(activeSessions(person)).isEqualTo(sessionsBefore);
        Instant expiresAt = Instant.parse(JsonPath.read(challenge, "$.expiresAt"));
        assertThat(expiresAt).isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(301));

        String session = verifyChallenge(challengeToken(challenge), code(secret, step))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(person.user().getId().toString()))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(activeSessions(person)).isEqualTo(sessionsBefore + 1);
        // El token sirve en un endpoint protegido (/auth/me exige ademas un rol activo al empleado).
        mockMvc.perform(get(MFA).header("Authorization", bearer(JsonPath.read(session, "$.token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // Un desafio ya usado no sirve otra vez, ni con un codigo nuevo.
        verifyChallenge(challengeToken(challenge), code(secret, step + 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));
    }

    @Test
    void emailMethodAndInvalidBodiesAreRejected() throws Exception {
        String token = sessionToken(employee());

        beginEnrollment(token, "{\"method\": \"email\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_METHOD_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.message").value("Método no disponible todavía."));
        beginEnrollment(token, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.method").value("Selecciona un método."));
        beginEnrollment(token, "{\"method\": \"sms\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.method").value("Selecciona un método válido."));
        beginEnrollment(token, "{\"method\": \"totp\", \"secret\": \"AAAA\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.secret").value("Campo no permitido."));
    }

    @Test
    void enrollmentWhileEnabledIsConflict() throws Exception {
        long step = safeStep();
        Person person = employee();
        String token = sessionToken(person);
        Enabled enabled = enable(token, step - 1);

        beginEnrollment(token, "{\"method\": \"totp\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_ALREADY_ENABLED"));

        // El secreto activo sigue siendo el mismo: el login se completa con el.
        verifyChallenge(challengeToken(login(person)), code(enabled.secret(), step)).andExpect(status().isOk());
    }

    @Test
    void repeatingEnrollmentReplacesThePendingSecret() throws Exception {
        long step = safeStep();
        String token = sessionToken(employee());
        byte[] first = beginTotp(token);
        byte[] second = beginTotp(token);

        verifyEnrollment(token, code(first, step))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        verifyEnrollment(token, code(second, step)).andExpect(status().isOk());
    }

    @Test
    void enrollmentVerifyHasAnAttemptLimit() throws Exception {
        long step = safeStep();
        Person person = employee();
        String token = sessionToken(person);
        byte[] secret = beginTotp(token);

        for (int attempt = 1; attempt <= 4; attempt++) {
            verifyEnrollment(token, "000000")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"))
                    .andExpect(jsonPath("$.message").value("El código no es correcto."));
        }
        verifyEnrollment(token, "000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_RESET"));

        // El secreto pendiente se descarto: ni el codigo correcto activa nada.
        verifyEnrollment(token, code(secret, step))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_NOT_PENDING"));
        assertThat(enrollment(person).getEnabled()).isFalse();
        assertThat(actions(person)).containsOnly("mfa_failed").hasSize(5);
    }

    // --- Codigos ---

    @Test
    void reusedCodeIsRejectedAndOneStepOfDriftIsAccepted() throws Exception {
        long step = safeStep();
        Person person = employee();
        Enabled enabled = enable(sessionToken(person), step - 1);

        String challenge = challengeToken(login(person));
        // El codigo de la activacion (paso anterior) ya se uso.
        verifyChallenge(challenge, code(enabled.secret(), step - 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"))
                .andExpect(jsonPath("$.message").value(CODE_INVALID));
        // Un paso adelante (reloj del telefono adelantado 30 s) se acepta.
        verifyChallenge(challenge, code(enabled.secret(), step + 1)).andExpect(status().isOk());

        // Ya se acepto el paso siguiente: el actual es anterior y tampoco se acepta.
        verifyChallenge(challengeToken(login(person)), code(enabled.secret(), step))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        assertThat(enrollment(person).getLastUsedStep()).isEqualTo(step + 1);
    }

    @Test
    void codeOutsideTheDriftWindowIsRejected() throws Exception {
        long step = safeStep();
        Person person = employee();
        Enabled enabled = enable(sessionToken(person), step - 1);
        String challenge = challengeToken(login(person));

        verifyChallenge(challenge, code(enabled.secret(), step + 2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        verifyChallenge(challenge, code(enabled.secret(), step)).andExpect(status().isOk());
    }

    @Test
    void recoveryCodeWorksOnlyOnceAndIsAudited() throws Exception {
        Tenant tenant = tenant();
        Person person = customer(tenant);
        Enabled enabled = enable(sessionToken(person), safeStep() - 1);
        String recoveryCode = enabled.recoveryCodes().get(0);

        // Se acepta sin importar mayusculas ni espacios.
        verifyChallenge(challengeToken(login(person)), "  " + recoveryCode.toLowerCase(Locale.ROOT) + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(person.user().getId().toString()));
        assertThat(actions(person)).containsExactly("mfa_enabled", "mfa_recovery_code_used");

        verifyChallenge(challengeToken(login(person)), recoveryCode)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        verifyChallenge(challengeToken(login(person)), enabled.recoveryCodes().get(1)).andExpect(status().isOk());
        assertThat(recoveryCodeRepository.findByUserId(person.user().getId()))
                .filteredOn(code -> code.getUsedAt() != null)
                .hasSize(2);
    }

    // --- Desafio ---

    @Test
    void expiredChallengeIsUnavailableAndDoesNotSpendTheCode() throws Exception {
        long step = safeStep();
        Person person = employee();
        Enabled enabled = enable(sessionToken(person), step - 1);
        String challenge = challengeToken(login(person));
        jdbcTemplate.update("UPDATE mfa_challenges SET expires_at = now() - interval '1 second' WHERE user_id = ?",
                person.user().getId());

        verifyChallenge(challenge, code(enabled.secret(), step))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(CHALLENGE_UNAVAILABLE));

        verifyChallenge(challengeToken(login(person)), code(enabled.secret(), step)).andExpect(status().isOk());
    }

    @Test
    void fiveWrongCodesExhaustTheChallengeAndLockTheAccount() throws Exception {
        long step = safeStep();
        Person person = employee();
        Enabled enabled = enable(sessionToken(person), step - 1);
        String challenge = challengeToken(login(person));

        for (int attempt = 1; attempt <= 4; attempt++) {
            verifyChallenge(challenge, "000000")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        }
        verifyChallenge(challenge, "000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));
        verifyChallenge(challenge, code(enabled.secret(), step))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));

        // Los fallos de codigo cuentan para el bloqueo, igual que la contrasena.
        AuthAccount account = account(person);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.temporarily_locked);
        login(person)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(actions(person)).filteredOn("mfa_failed"::equals).hasSize(5);
    }

    @Test
    void newLoginReplacesThePreviousChallenge() throws Exception {
        long step = safeStep();
        Person person = employee();
        Enabled enabled = enable(sessionToken(person), step - 1);
        String first = challengeToken(login(person));
        String second = challengeToken(login(person));

        verifyChallenge(first, code(enabled.secret(), step))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));
        verifyChallenge(second, code(enabled.secret(), step)).andExpect(status().isOk());
    }

    @Test
    void lockedAccountRejectsEvenACorrectCode() throws Exception {
        long step = safeStep();
        Person person = employee();
        Enabled enabled = enable(sessionToken(person), step - 1);
        String challenge = challengeToken(login(person));
        AuthAccount account = account(person);
        account.setStatus(AccountStatus.temporarily_locked);
        account.setLockedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));
        authAccountRepository.save(account);

        verifyChallenge(challenge, code(enabled.secret(), step))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));

        // No se gasto el codigo ni se conto como fallo.
        assertThat(enrollment(person).getLastUsedStep()).isEqualTo(step - 1);
        assertThat(actions(person)).doesNotContain("mfa_failed");
        assertThat(activeSessions(person)).isEqualTo(1);
    }

    @Test
    void unknownChallengeTokenIsUnavailable() throws Exception {
        verifyChallenge("token-inventado", "123456")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));
        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\": \"x\", \"code\": \"123456\", \"userId\": \"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.userId").value("Campo no permitido."));
    }

    // --- Desactivar y cambio de contrasena ---

    @Test
    void disableRequiresTheCurrentPassword() throws Exception {
        Person person = employee();
        String token = sessionToken(person);
        enable(token, safeStep() - 1);

        disable(token, "Incorrecta12345!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.currentPassword").value("La contraseña actual no es correcta."));
        assertThat(enrollment(person).getEnabled()).isTrue();

        disable(token, PASSWORD).andExpect(status().isNoContent());
        MfaEnrollment disabled = enrollment(person);
        assertThat(disabled.getEnabled()).isFalse();
        assertThat(disabled.getSecretCiphertext()).isNull();
        assertThat(recoveryCodeRepository.findByUserId(person.user().getId())).isEmpty();
        assertThat(actions(person)).containsExactly("mfa_enabled", "mfa_disabled");

        // Sin MFA el login vuelve a entregar la sesion directamente.
        login(person).andExpect(status().isOk()).andExpect(jsonPath("$.token").exists());
    }

    @Test
    void passwordChangeWithMfaRequiresACode() throws Exception {
        long step = safeStep();
        Person person = employee();
        String token = sessionToken(person);
        Enabled enabled = enable(token, step - 1);
        String hashBefore = account(person).getPasswordHash();

        changePassword(token, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mfaCode").value("El código de verificación en dos pasos no es correcto."));
        changePassword(token, "000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mfaCode").exists());
        assertThat(account(person).getPasswordHash()).isEqualTo(hashBefore);
        // El fallo queda auditado aunque el cambio se deshaga.
        assertThat(actions(person)).filteredOn("mfa_failed"::equals).hasSize(2);

        changePassword(token, code(enabled.secret(), step)).andExpect(status().isNoContent());
        assertThat(account(person).getPasswordHash()).isNotEqualTo(hashBefore);
    }

    @Test
    void mfaEndpointsNeedASession() throws Exception {
        mockMvc.perform(get(MFA)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ENROLLMENT).contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"totp\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(DISABLE).contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\": \"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Utilidades ---

    private record Person(Tenant tenant, User user) {
    }

    private record Enabled(byte[] secret, List<String> recoveryCodes) {
    }

    /**
     * Paso TOTP actual, esperando al siguiente si quedan menos de 12 s: asi el test completo corre dentro del
     * mismo paso y los codigos de {@code step - 1}, {@code step} y {@code step + 1} siguen en la ventana.
     */
    private static long safeStep() throws InterruptedException {
        long secondsIntoStep = Instant.now().getEpochSecond() % Totp.STEP_SECONDS;
        if (secondsIntoStep > Totp.STEP_SECONDS - 12) {
            Thread.sleep((Totp.STEP_SECONDS - secondsIntoStep + 1) * 1000);
        }
        return Totp.step(Instant.now());
    }

    private static String code(byte[] secret, long step) {
        return Totp.generate(secret, step);
    }

    private Enabled enable(String token, long activationStep) throws Exception {
        byte[] secret = beginTotp(token);
        String body = verifyEnrollment(token, code(secret, activationStep))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Enabled(secret, JsonPath.read(body, "$.recoveryCodes"));
    }

    private byte[] beginTotp(String token) throws Exception {
        String body = beginEnrollment(token, "{\"method\": \"totp\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Totp.base32Decode(JsonPath.read(body, "$.secret"));
    }

    private ResultActions beginEnrollment(String token, String body) throws Exception {
        return mockMvc.perform(post(ENROLLMENT).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions verifyEnrollment(String token, String code) throws Exception {
        return mockMvc.perform(post(ENROLLMENT_VERIFY).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\"}".formatted(code)));
    }

    private ResultActions verifyChallenge(String challengeToken, String code) throws Exception {
        return mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\": \"%s\", \"code\": \"%s\"}".formatted(challengeToken, code)));
    }

    private ResultActions disable(String token, String currentPassword) throws Exception {
        return mockMvc.perform(post(DISABLE).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"%s\"}".formatted(currentPassword)));
    }

    private ResultActions changePassword(String token, String mfaCode) throws Exception {
        return mockMvc.perform(post(PASSWORD_CHANGE).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"%s\", \"newPassword\": \"OtraClave12345!\", \"mfaCode\": %s}"
                        .formatted(PASSWORD, mfaCode == null ? "null" : "\"" + mfaCode + "\"")));
    }

    private ResultActions login(Person person) throws Exception {
        String slug = person.user().getType() == UserType.customer ? "\"" + person.tenant().getSlug() + "\"" : "null";
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\", \"tenantSlug\": %s}"
                        .formatted(person.user().getEmail(), PASSWORD, slug)));
    }

    /** Login de una cuenta sin MFA todavia: entrega la sesion directamente. */
    private String sessionToken(Person person) throws Exception {
        String body = login(person).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private static String challengeToken(ResultActions loginResult) throws Exception {
        return challengeToken(loginResult.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String challengeToken(String loginBody) {
        Map<String, Object> body = JsonPath.read(loginBody, "$");
        assertThat(body).containsEntry("mfaRequired", true);
        return (String) body.get("challengeToken");
    }

    private MfaEnrollment enrollment(Person person) {
        return enrollmentRepository.findByUserId(person.user().getId()).orElseThrow();
    }

    private AuthAccount account(Person person) {
        return authAccountRepository.findByUserId(person.user().getId()).orElseThrow();
    }

    private int activeSessions(Person person) {
        return sessionRepository.findByUserIdAndRevokedAtIsNull(person.user().getId()).size();
    }

    private List<String> actions(Person person) {
        return auditLogRepository.findByAuthAccountIdOrderByCreatedAtAsc(account(person).getId()).stream()
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

    private Person employee() {
        return person(tenant(), UserType.employee);
    }

    private Person customer(Tenant tenant) {
        return person(tenant, UserType.customer);
    }

    private Person person(Tenant tenant, UserType type) {
        String email = "mfa-" + UUID.randomUUID() + "@test.local";
        User user = User.builder().name("Usuario test").email(email).type(type).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(AccountStatus.active)
                .build());
        return new Person(tenant, user);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
