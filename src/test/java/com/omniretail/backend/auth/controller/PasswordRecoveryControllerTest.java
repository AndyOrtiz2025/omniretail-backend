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
import com.omniretail.backend.auth.entity.PasswordResetChallenge;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.PasswordResetChallengeRepository;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.notification.EmailMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class PasswordRecoveryControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String FORGOT = "/api/v1/auth/password/forgot";
    private static final String RESET = "/api/v1/auth/password/reset";
    private static final String OLD_PASSWORD = "Anterior123!";
    private static final String NEW_CUSTOMER_PASSWORD = "Nueva123!";
    private static final String NEW_EMPLOYEE_PASSWORD = "NuevaSegura123!";
    private static final String GENERIC_BODY = "{\"message\":\"Si existe una cuenta asociada, recibirás instrucciones.\"}";
    private static final Pattern TOKEN = Pattern.compile("/restablecer-contrasena/([A-Za-z0-9_-]{43})");

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
    private PasswordResetChallengeRepository challengeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- Solicitud (forgot) ---

    @Test
    void unknownEmailGetsSameResponseAndNoEmail() throws Exception {
        String email = uniqueEmail();
        forgot(email, null).andExpect(status().isAccepted()).andExpect(result ->
                assertThat(result.getResponse().getContentAsString()).isEqualTo(GENERIC_BODY));

        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        forgot(employee.getEmail(), null).andExpect(status().isAccepted()).andExpect(result ->
                assertThat(result.getResponse().getContentAsString()).isEqualTo(GENERIC_BODY));

        assertThat(emailSender.settledMessagesTo(email)).isEmpty();
    }

    @Test
    void customerNeedsTheStoreSlug() throws Exception {
        Tenant tenant = tenant();
        User customer = account(tenant, UserType.customer, uniqueEmail(), AccountStatus.active);

        forgot(customer.getEmail(), null).andExpect(status().isAccepted());
        forgot(customer.getEmail(), "otra-tienda-" + UUID.randomUUID()).andExpect(status().isAccepted());
        assertThat(emailSender.settledMessagesTo(customer.getEmail())).isEmpty();
        assertThat(challengeRepository.findByUserId(customer.getId())).isEmpty();

        forgot(customer.getEmail(), tenant.getSlug()).andExpect(status().isAccepted());
        EmailMessage message = emailSender.awaitMessageTo(customer.getEmail());
        assertThat(message.body()).contains("http://localhost:3000/tienda/" + tenant.getSlug() + "/restablecer-contrasena/");
        assertThat(challengeRepository.findByUserId(customer.getId())).hasSize(1);
    }

    @Test
    void employeeRecoversWithoutSlug() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);

        forgot(employee.getEmail(), null).andExpect(status().isAccepted());

        EmailMessage message = emailSender.awaitMessageTo(employee.getEmail());
        assertThat(message.body()).contains("http://localhost:3000/restablecer-contrasena/");
        assertThat(message.body()).doesNotContain("/tienda/");
        PasswordResetChallenge challenge = challengeRepository.findByUserId(employee.getId()).getFirst();
        assertThat(challenge.getExpiresAt()).isBetween(
                challenge.getCreatedAt().plusSeconds(14 * 60), challenge.getCreatedAt().plusSeconds(16 * 60));
    }

    @Test
    void fourthRequestWithinCooldownIsSilentlySkipped() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);

        for (int i = 0; i < 4; i++) {
            forgot(employee.getEmail(), null).andExpect(status().isAccepted()).andExpect(result ->
                    assertThat(result.getResponse().getContentAsString()).isEqualTo(GENERIC_BODY));
        }

        assertThat(challengeRepository.findByUserId(employee.getId())).hasSize(3);
        emailSender.awaitMessagesTo(employee.getEmail(), 3);
        assertThat(emailSender.settledMessagesTo(employee.getEmail())).hasSize(3);
    }

    @Test
    void newRequestSupersedesPreviousLink() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        forgot(employee.getEmail(), null).andExpect(status().isAccepted());
        String first = tokenFrom(emailSender.awaitMessageTo(employee.getEmail()));
        forgot(employee.getEmail(), null).andExpect(status().isAccepted());
        String second = tokenFrom(emailSender.awaitMessagesTo(employee.getEmail(), 2).getLast());

        List<PasswordResetChallenge> challenges = challengeRepository.findByUserId(employee.getId()).stream()
                .sorted(Comparator.comparing(PasswordResetChallenge::getCreatedAt))
                .toList();
        assertThat(challenges.get(0).getSupersededAt()).isNotNull();
        assertThat(challenges.get(1).getSupersededAt()).isNull();

        reset(first, NEW_EMPLOYEE_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
        reset(second, NEW_EMPLOYEE_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void ineligibleAccountsGetNoEmail() throws Exception {
        Tenant tenant = tenant();
        List<User> users = List.of(
                account(tenant, UserType.customer, uniqueEmail(), AccountStatus.pending_verification),
                account(tenant, UserType.employee, uniqueEmail(), AccountStatus.password_reset_required),
                account(tenant, UserType.employee, uniqueEmail(), AccountStatus.disabled),
                account(tenant, UserType.employee, uniqueEmail(), AccountStatus.archived));

        for (User user : users) {
            forgot(user.getEmail(), tenant.getSlug()).andExpect(status().isAccepted());
        }
        for (User user : users) {
            assertThat(emailSender.settledMessagesTo(user.getEmail())).isEmpty();
            assertThat(challengeRepository.findByUserId(user.getId())).isEmpty();
        }
    }

    @Test
    void malformedEmailReturnsValidationError() throws Exception {
        forgot("no-es-correo", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.email").value("Ingrese un correo con formato válido."));
        forgot("", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").value("El correo es obligatorio."));
    }

    // --- Restablecer (reset) ---

    @Test
    void resetChangesPasswordAndRevokesAllSessions() throws Exception {
        Tenant tenant = tenant();
        User customer = account(tenant, UserType.customer, uniqueEmail(), AccountStatus.active);
        String oldJwt = JsonPath.read(login(customer.getEmail(), OLD_PASSWORD, tenant.getSlug())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
        mockMvc.perform(get(ME).header("Authorization", "Bearer " + oldJwt)).andExpect(status().isOk());

        forgot(customer.getEmail(), tenant.getSlug()).andExpect(status().isAccepted());
        String token = tokenFrom(emailSender.awaitMessageTo(customer.getEmail()));
        reset(token, NEW_CUSTOMER_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("customer"))
                .andExpect(jsonPath("$.tenantSlug").value(tenant.getSlug()));

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + oldJwt)).andExpect(status().isUnauthorized());
        login(customer.getEmail(), OLD_PASSWORD, tenant.getSlug()).andExpect(status().isUnauthorized());
        login(customer.getEmail(), NEW_CUSTOMER_PASSWORD, tenant.getSlug()).andExpect(status().isOk());

        AuthAccount account = authAccountRepository.findByUserId(customer.getId()).orElseThrow();
        assertThat(account.getPasswordChangedAt()).isNotNull();
        assertThat(challengeRepository.findByUserId(customer.getId()).getFirst().getUsedAt()).isNotNull();
    }

    @Test
    void resetUnlocksTemporarilyLockedAccount() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        AuthAccount account = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
        account.setStatus(AccountStatus.temporarily_locked);
        account.setFailedLoginAttempts(5);
        account.setLockedUntil(Instant.now().plusSeconds(15 * 60));
        authAccountRepository.save(account);

        forgot(employee.getEmail(), null).andExpect(status().isAccepted());
        String token = tokenFrom(emailSender.awaitMessageTo(employee.getEmail()));
        reset(token, NEW_EMPLOYEE_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("employee"))
                .andExpect(jsonPath("$.tenantSlug").doesNotExist());

        AuthAccount unlocked = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
        assertThat(unlocked.getStatus()).isEqualTo(AccountStatus.active);
        assertThat(unlocked.getFailedLoginAttempts()).isZero();
        assertThat(unlocked.getLockedUntil()).isNull();
        login(employee.getEmail(), NEW_EMPLOYEE_PASSWORD, null).andExpect(status().isOk());
    }

    @Test
    void usedOrExpiredTokenIsRejected() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        forgot(employee.getEmail(), null).andExpect(status().isAccepted());
        String used = tokenFrom(emailSender.awaitMessageTo(employee.getEmail()));
        reset(used, NEW_EMPLOYEE_PASSWORD).andExpect(status().isOk());

        User other = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        forgot(other.getEmail(), null).andExpect(status().isAccepted());
        String expired = tokenFrom(emailSender.awaitMessageTo(other.getEmail()));
        jdbcTemplate.update("UPDATE password_reset_challenges SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), other.getId());

        for (String invalid : List.of(used, expired, "token-que-no-existe")) {
            reset(invalid, "OtraClave123!!")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"))
                    .andExpect(jsonPath("$.message").value("Este enlace no es válido o ya expiró."));
        }
        assertThat(passwordEncoder.matches(OLD_PASSWORD,
                authAccountRepository.findByUserId(other.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void employeeNeedsTwelveCharactersAndTokenSurvivesRejection() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        forgot(employee.getEmail(), null).andExpect(status().isAccepted());
        String token = tokenFrom(emailSender.awaitMessageTo(employee.getEmail()));

        reset(token, NEW_CUSTOMER_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.newPassword").value(
                        "La contraseña debe tener entre 12 y 24 caracteres e incluir una mayúscula, una minúscula, "
                                + "un número y un carácter especial."));
        assertThat(challengeRepository.findByUserId(employee.getId()).getFirst().getUsedAt()).isNull();

        reset(token, NEW_EMPLOYEE_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void disabledOrArchivedAccountsNeverChange() throws Exception {
        for (AccountStatus blocked : List.of(AccountStatus.disabled, AccountStatus.archived)) {
            User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
            forgot(employee.getEmail(), null).andExpect(status().isAccepted());
            String token = tokenFrom(emailSender.awaitMessageTo(employee.getEmail()));
            AuthAccount account = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
            account.setStatus(blocked);
            authAccountRepository.save(account);

            reset(token, NEW_EMPLOYEE_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));

            AuthAccount after = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(blocked);
            assertThat(passwordEncoder.matches(OLD_PASSWORD, after.getPasswordHash())).isTrue();
            assertThat(challengeRepository.findByUserId(employee.getId()).getFirst().getUsedAt()).isNull();
        }
    }

    @Test
    void noPlainTokenIsStoredInDatabase() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail(), AccountStatus.active);
        forgot(employee.getEmail(), null).andExpect(status().isAccepted());
        String token = tokenFrom(emailSender.awaitMessageTo(employee.getEmail()));

        PasswordResetChallenge challenge = challengeRepository.findByUserId(employee.getId()).getFirst();
        assertThat(challenge.getTokenHash()).hasSize(64).isNotEqualTo(token);
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_challenges WHERE token_hash LIKE ?", Integer.class, "%" + token + "%");
        assertThat(matches).isZero();
    }

    // --- Utilidades ---

    private ResultActions forgot(String email, String tenantSlug) throws Exception {
        return mockMvc.perform(post(FORGOT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": %s, \"tenantSlug\": %s}".formatted(json(email), json(tenantSlug))));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post(RESET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": %s, \"newPassword\": %s}".formatted(json(token), json(newPassword))));
    }

    private ResultActions login(String email, String password, String tenantSlug) throws Exception {
        return mockMvc.perform(post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": %s, \"password\": %s, \"tenantSlug\": %s}"
                        .formatted(json(email), json(password), json(tenantSlug))));
    }

    private static String tokenFrom(EmailMessage message) {
        Matcher matcher = TOKEN.matcher(message.body());
        assertThat(matcher.find()).as("el correo trae el enlace de recuperacion").isTrue();
        return matcher.group(1);
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

    private User account(Tenant tenant, UserType type, String email, AccountStatus status) {
        User user = User.builder().name("Usuario test").email(email).type(type).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(OLD_PASSWORD))
                .status(status)
                .build());
        return user;
    }

    private static String uniqueEmail() {
        return "usuario-" + UUID.randomUUID() + "@test.local";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
