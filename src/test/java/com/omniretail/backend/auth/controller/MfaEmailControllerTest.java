package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.omniretail.backend.auth.entity.MfaChallenge;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.MfaChallengeRepository;
import com.omniretail.backend.auth.service.Totp;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.notification.EmailMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Verificacion en dos pasos por correo. Los codigos se leen de los correos capturados
 * ({@link CapturingEmailSender}); el reloj de auth se adelanta a mano para probar esperas y vencimientos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class MfaEmailControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String MFA = "/api/v1/auth/mfa";
    private static final String PASSWORD = "Correcta12345!";
    private static final String WRONG = "Incorrecta12345!";
    private static final Pattern CODE = Pattern.compile("es: (\\d{6})");
    private static final MutableClock CLOCK = new MutableClock();
    private static final Duration WAIT = Duration.ofSeconds(61);

    @TestBean(name = "authClock")
    private Clock authClock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CapturingEmailSender emailSender;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private MfaChallengeRepository challengeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    static Clock authClock() {
        return CLOCK;
    }

    @BeforeEach
    void resetClock() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // --- Activacion ---

    @Test
    void emailEnrollmentSendsACodeAndActivates() throws Exception {
        User user = employee();
        String token = sessionToken(user);

        authed(token, MFA + "/enrollment", "{\"method\": \"email\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("email"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.otpauthUri").doesNotExist());
        EmailMessage mail = emailSender.awaitMessageTo(user.getEmail());
        assertThat(mail.subject()).isEqualTo("Tu código de verificación");
        assertThat(mail.body()).contains("activar la verificación en dos pasos", "Vence en 5 minutos");
        mockMvc.perform(get(MFA).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.method").value("email"));

        authed(token, MFA + "/enrollment/verify", "{\"code\": \"%s\"}".formatted(codeOf(mail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(8));
        mockMvc.perform(get(MFA).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.method").value("email"));

        authed(token, MFA + "/enrollment", "{\"method\": \"email\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_ALREADY_ENABLED"));
    }

    @Test
    void repeatingEmailEnrollmentRespectsTheWaitAndReplacesTheCode() throws Exception {
        User user = employee();
        String token = sessionToken(user);
        authed(token, MFA + "/enrollment", "{\"method\": \"email\"}").andExpect(status().isOk());
        String first = codeOf(emailSender.awaitMessageTo(user.getEmail()));

        authed(token, MFA + "/enrollment", "{\"method\": \"email\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MFA_CODE_RESEND_LIMITED"));

        CLOCK.advance(WAIT);
        authed(token, MFA + "/enrollment", "{\"method\": \"email\"}").andExpect(status().isOk());
        String second = codeOf(emailSender.awaitMessagesTo(user.getEmail(), 2).getLast());
        if (!first.equals(second)) {
            authed(token, MFA + "/enrollment/verify", "{\"code\": \"%s\"}".formatted(first))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        }
        authed(token, MFA + "/enrollment/verify", "{\"code\": \"%s\"}".formatted(second)).andExpect(status().isOk());
    }

    // --- Login ---

    @Test
    void loginSendsACodeAndVerifyGivesTheSession() throws Exception {
        User user = employee();
        List<String> recoveryCodes = enableEmailMfa(user);
        CLOCK.advance(WAIT);

        String login = login(user, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.method").value("email"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(login, "$.expiresAt"))).isEqualTo(CLOCK.instant().plus(5, ChronoUnit.MINUTES));
        EmailMessage mail = emailSender.awaitMessagesTo(user.getEmail(), 2).getLast();
        assertThat(mail.body()).contains("iniciar sesión");
        String code = codeOf(mail);
        // El codigo solo viaja en el correo.
        assertThat(login).doesNotContain(code);

        String session = verify(JsonPath.read(login, "$.challengeToken"), code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(session).doesNotContain(code);
        verify(JsonPath.read(login, "$.challengeToken"), code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));

        // Los codigos de recuperacion funcionan igual que con la app.
        CLOCK.advance(WAIT);
        verify(challengeToken(user), recoveryCodes.get(0)).andExpect(status().isOk());
    }

    @Test
    void mfaEmailHasNoLinks() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);
        challengeToken(user);
        String token = sessionTokenAfterMfa(user);
        CLOCK.advance(WAIT);
        authed(token, MFA + "/code", "").andExpect(status().isNoContent());

        List<EmailMessage> mails = emailSender.awaitMessagesTo(user.getEmail(), 4);
        assertThat(mails).allSatisfy(mail -> {
            assertThat(mail.body()).doesNotContainIgnoringCase("http").doesNotContain("www.");
            assertThat(mail.body()).containsPattern("es: \\d{6}");
        });
    }

    /** Con la espera activa no sale el correo, pero el login responde exactamente igual. */
    @Test
    void rateLimitedLoginRespondsExactlyLikeANormalLogin() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);

        String sent = login(user, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String limited = login(user, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(withoutChallengeToken(limited)).isEqualTo(withoutChallengeToken(sent));
        Map<String, Object> body = JsonPath.read(limited, "$");
        assertThat(body).containsOnlyKeys("mfaRequired", "challengeToken", "method", "expiresAt");
        // Activacion + primer login: el segundo login no envio correo.
        assertThat(emailSender.settledMessagesTo(user.getEmail())).hasSize(2);
        assertThat(liveChallenge(user).getCodeHash()).isNull();

        // El usuario pide el codigo con "reenviar" cuando pasa la espera.
        CLOCK.advance(WAIT);
        resend(JsonPath.read(limited, "$.challengeToken")).andExpect(status().isNoContent());
        String code = codeOf(emailSender.awaitMessagesTo(user.getEmail(), 3).getLast());
        verify(JsonPath.read(limited, "$.challengeToken"), code).andExpect(status().isOk());
    }

    @Test
    void failedPasswordSendsNoEmail() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);

        login(user, WRONG).andExpect(status().isUnauthorized());

        assertThat(emailSender.settledMessagesTo(user.getEmail())).hasSize(1);
    }

    // --- Reenviar ---

    @Test
    void resendWaitsInvalidatesTheOldCodeAndKeepsAttempts() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);
        String challenge = challengeToken(user);
        String first = codeOf(emailSender.awaitMessagesTo(user.getEmail(), 2).getLast());
        verify(challenge, "000000").andExpect(status().isUnauthorized());

        resend(challenge)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MFA_CODE_RESEND_LIMITED"))
                .andExpect(jsonPath("$.message").value("Espera un momento antes de pedir otro código."));

        CLOCK.advance(WAIT);
        String resent = resend(challenge).andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();
        String second = codeOf(emailSender.awaitMessagesTo(user.getEmail(), 3).getLast());
        assertThat(resent).isEmpty();
        assertThat(liveChallenge(user).getFailedAttempts()).isEqualTo(1);
        assertThat(liveChallenge(user).getCodeExpiresAt()).isEqualTo(CLOCK.instant().plus(5, ChronoUnit.MINUTES));
        if (!first.equals(second)) {
            verify(challenge, first)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        }
        verify(challenge, second).andExpect(status().isOk());
    }

    @Test
    void atMostThreeResendsPerChallenge() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);
        String challenge = challengeToken(user);

        for (int resend = 1; resend <= 3; resend++) {
            CLOCK.advance(WAIT);
            resend(challenge).andExpect(status().isNoContent());
        }
        CLOCK.advance(WAIT);
        resend(challenge)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MFA_CODE_RESEND_LIMITED"));
        assertThat(liveChallenge(user).getResendCount()).isEqualTo(3);
    }

    @Test
    void codeExpiresAfterFiveMinutesAndTheChallengeAfterFifteen() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);
        String challenge = challengeToken(user);
        String code = codeOf(emailSender.awaitMessagesTo(user.getEmail(), 2).getLast());

        CLOCK.advance(Duration.ofMinutes(5));
        verify(challenge, code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));

        // El desafio sigue vivo: un reenvio da otro codigo de 5 minutos.
        resend(challenge).andExpect(status().isNoContent());
        CLOCK.advance(Duration.ofMinutes(4));
        resend(challenge).andExpect(status().isNoContent());
        CLOCK.advance(Duration.ofMinutes(4));
        resend(challenge).andExpect(status().isNoContent());
        // Minuto 14 (+61 s del inicio): el codigo nuevo no pasa del limite de 15 min del desafio.
        assertThat(liveChallenge(user).getCodeExpiresAt()).isEqualTo(liveChallenge(user).getExpiresAt());

        CLOCK.advance(Duration.ofMinutes(2));
        verify(challenge, "123456")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));
        resend(challenge)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_UNAVAILABLE"));
    }

    @Test
    void atMostTenEmailsPerUserPerHour() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        Instant firstEmail = CLOCK.instant();

        // 1 (activacion) + 9 logins = 10 correos en la hora.
        for (int login = 1; login <= 9; login++) {
            CLOCK.advance(WAIT);
            challengeToken(user);
        }
        assertThat(emailSender.awaitMessagesTo(user.getEmail(), 10)).hasSize(10);

        CLOCK.advance(WAIT);
        String limited = challengeToken(user);
        resend(limited).andExpect(status().isTooManyRequests());
        assertThat(emailSender.settledMessagesTo(user.getEmail())).hasSize(10);

        // Pasada la hora se puede volver a enviar.
        CLOCK.set(firstEmail.plus(Duration.ofHours(1)).plusSeconds(1));
        String fresh = challengeToken(user);
        emailSender.awaitMessagesTo(user.getEmail(), 11);
        assertThat(fresh).isNotBlank();
    }

    @Test
    void wrongEmailCodesCountTowardTheLockout() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);

        for (int attempt = 1; attempt <= 3; attempt++) {
            login(user, WRONG).andExpect(status().isUnauthorized());
            CLOCK.advance(Duration.ofSeconds(1));
        }
        String challenge = challengeToken(user);
        verify(challenge, "000000").andExpect(status().isUnauthorized());
        CLOCK.advance(Duration.ofSeconds(1));
        verify(challenge, "000000").andExpect(status().isUnauthorized());

        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.temporarily_locked);
    }

    @Test
    void resendIsNotAvailableForAuthenticatorApps() throws Exception {
        User user = employee();
        String token = sessionToken(user);
        String begin = authed(token, MFA + "/enrollment", "{\"method\": \"totp\"}")
                .andReturn().getResponse().getContentAsString();
        byte[] secret = Totp.base32Decode(JsonPath.read(begin, "$.secret"));
        authed(token, MFA + "/enrollment/verify",
                "{\"code\": \"%s\"}".formatted(Totp.generate(secret, Totp.step(CLOCK.instant()))))
                .andExpect(status().isOk());

        resend(challengeToken(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_RESEND_NOT_AVAILABLE"));
        authed(token, MFA + "/code", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_EMAIL_CODE_NOT_AVAILABLE"));
        assertThat(emailSender.settledMessagesTo(user.getEmail())).isEmpty();
    }

    // --- Cambio de contrasena ---

    @Test
    void passwordChangeWithEmailMfaUsesACodeSentOnRequest() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);
        String token = sessionTokenAfterMfa(user);

        changePassword(token, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mfaCode").exists());

        CLOCK.advance(WAIT);
        authed(token, MFA + "/code", "").andExpect(status().isNoContent());
        authed(token, MFA + "/code", "").andExpect(status().isTooManyRequests());
        EmailMessage mail = emailSender.awaitMessagesTo(user.getEmail(), 3).getLast();
        assertThat(mail.body()).contains("cambiar tu contraseña");

        changePassword(token, codeOf(mail)).andExpect(status().isNoContent());
    }

    @Test
    void passwordChangeEmailCodeHasAnAttemptLimit() throws Exception {
        User user = employee();
        enableEmailMfa(user);
        CLOCK.advance(WAIT);
        String token = sessionTokenAfterMfa(user);
        CLOCK.advance(WAIT);
        authed(token, MFA + "/code", "").andExpect(status().isNoContent());
        String code = codeOf(emailSender.awaitMessagesTo(user.getEmail(), 3).getLast());
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 5; attempt++) {
            changePassword(token, wrong).andExpect(status().isBadRequest());
        }
        // Al 5.o fallo el codigo se descarta: ya no sirve ni el correcto.
        changePassword(token, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mfaCode").exists());
    }

    @Test
    void codeEndpointNeedsEmailMfaAndASession() throws Exception {
        mockMvc.perform(post(MFA + "/code")).andExpect(status().isUnauthorized());
        String token = sessionToken(employee());
        authed(token, MFA + "/code", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_EMAIL_CODE_NOT_AVAILABLE"));
    }

    // --- Utilidades ---

    /** Activa el MFA por correo (1 correo) y devuelve los codigos de recuperacion. */
    private List<String> enableEmailMfa(User user) throws Exception {
        String token = sessionToken(user);
        authed(token, MFA + "/enrollment", "{\"method\": \"email\"}").andExpect(status().isOk());
        String code = codeOf(emailSender.awaitMessageTo(user.getEmail()));
        String body = authed(token, MFA + "/enrollment/verify", "{\"code\": \"%s\"}".formatted(code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.recoveryCodes");
    }

    /** Login con MFA por correo completo: abre el desafio, lee el ultimo correo y devuelve el token. */
    private String sessionTokenAfterMfa(User user) throws Exception {
        int before = emailSender.settledMessagesTo(user.getEmail()).size();
        CLOCK.advance(WAIT);
        String challenge = challengeToken(user);
        String code = codeOf(emailSender.awaitMessagesTo(user.getEmail(), before + 1).getLast());
        String body = verify(challenge, code).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private static String codeOf(EmailMessage mail) {
        Matcher matcher = CODE.matcher(mail.body());
        assertThat(matcher.find()).as("el correo trae el codigo").isTrue();
        return matcher.group(1);
    }

    private String challengeToken(User user) throws Exception {
        String body = login(user, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.challengeToken");
    }

    private MfaChallenge liveChallenge(User user) {
        return challengeRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId()).getFirst();
    }

    private ResultActions verify(String challengeToken, String code) throws Exception {
        return mockMvc.perform(post(MFA + "/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\": \"%s\", \"code\": \"%s\"}".formatted(challengeToken, code)));
    }

    private ResultActions resend(String challengeToken) throws Exception {
        return mockMvc.perform(post(MFA + "/resend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\": \"%s\"}".formatted(challengeToken)));
    }

    private ResultActions changePassword(String token, String mfaCode) throws Exception {
        return authed(token, "/api/v1/auth/password/change",
                "{\"currentPassword\": \"%s\", \"newPassword\": \"OtraClave12345!\", \"mfaCode\": %s}"
                        .formatted(PASSWORD, mfaCode == null ? "null" : "\"" + mfaCode + "\""));
    }

    private ResultActions authed(String token, String path, String body) throws Exception {
        var request = post(path)
                .header("Authorization", "Bearer " + token);
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private String sessionToken(User user) throws Exception {
        return JsonPath.read(login(user, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions login(User user, String password) throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(user.getEmail(), password)));
    }

    private AuthAccount account(User user) {
        return authAccountRepository.findByUserId(user.getId()).orElseThrow();
    }

    private static String withoutChallengeToken(String body) {
        return body.replaceAll("\"challengeToken\":\"[^\"]*\"", "");
    }

    private User employee() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        String email = "mfa-email-" + UUID.randomUUID() + "@test.local";
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

    /** Reloj que el test adelanta a mano (se reinicia en cada test). */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
            now.set(now.get().plus(duration));
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
