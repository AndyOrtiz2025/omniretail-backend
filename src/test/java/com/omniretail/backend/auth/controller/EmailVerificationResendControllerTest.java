package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmailVerification;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.EmailVerificationRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * B-08: un correo registrado y no verificado nunca queda "secuestrado". Reenvio del enlace
 * ({@code POST /auth/email/resend}) y re-registro de una cuenta pendiente con el enlace vencido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
class EmailVerificationResendControllerTest {

    private static final String RESEND = "/api/v1/auth/email/resend";
    private static final String VERIFY = "/api/v1/auth/email/verify";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "Segura123!";
    private static final String OTHER_PASSWORD = "OtraClave456#";
    private static final String ACCEPTED_MESSAGE = "Si el correo es válido, te enviamos un mensaje para continuar.";
    private static final Pattern VERIFY_LINK = Pattern.compile("http://localhost:3000/verificar-correo/([A-Za-z0-9_-]{43})");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CapturingEmailSender emailSender;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- Reenvio ---

    @Test
    void resendSendsNewLinkAndInvalidatesThePreviousOne() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, PASSWORD);
        String firstToken = tokenFrom(emailSender.awaitMessageTo(email));

        resend("  " + email.toUpperCase() + " ", tenant.getSlug())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        EmailMessage second = emailSender.awaitMessagesTo(email, 2).getLast();
        assertThat(second.purpose()).isEqualTo(EmailPurpose.EMAIL_VERIFICATION);
        String secondToken = tokenFrom(second);
        assertThat(secondToken).isNotEqualTo(firstToken);

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        List<EmailVerification> verifications = emailVerificationRepository.findByUserId(user.getId());
        assertThat(verifications).hasSize(2);
        assertThat(verifications).filteredOn(verification -> verification.getSupersededAt() != null).hasSize(1);
        // Solo hashes en la BD, nunca el token en claro.
        assertThat(verifications).extracting(EmailVerification::getTokenHash)
                .doesNotContain(firstToken, secondToken)
                .allSatisfy(hash -> assertThat(hash).hasSize(64));

        // El enlace anterior ya no sirve (mismo error generico); el nuevo activa la cuenta.
        verify(firstToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
        verify(secondToken).andExpect(status().isOk());
        assertThat(authAccountRepository.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.active);
        // De un solo uso.
        verify(secondToken).andExpect(status().isBadRequest());
    }

    @Test
    void resendAnswersTheSameForPendingUnknownVerifiedEmployeeAndUnknownStore() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String pending = uniqueEmail();
        register(tenant.getSlug(), "Ana", pending, PASSWORD);
        emailSender.awaitMessageTo(pending);

        String verified = uniqueEmail();
        register(tenant.getSlug(), "Beto", verified, PASSWORD);
        verify(tokenFrom(emailSender.awaitMessageTo(verified))).andExpect(status().isOk());

        String employeeEmail = employee(tenant);
        String unknown = uniqueEmail();

        String pendingBody = resendBody(pending, tenant.getSlug());
        for (String body : List.of(
                resendBody(unknown, tenant.getSlug()),
                resendBody(verified, tenant.getSlug()),
                resendBody(employeeEmail, tenant.getSlug()),
                resendBody(pending, "tienda-que-no-existe"))) {
            assertThat(withoutTimestamp(body)).isEqualTo(withoutTimestamp(pendingBody));
        }

        assertThat(emailSender.awaitMessagesTo(pending, 2)).hasSize(2);
        assertThat(emailSender.settledMessagesTo(unknown)).isEmpty();
        assertThat(emailSender.settledMessagesTo(employeeEmail)).isEmpty();
        assertThat(emailSender.settledMessagesTo(verified)).hasSize(1); // Solo el enlace del registro.
        User verifiedUser = userRepository.findByTenantIdAndEmail(tenant.getId(), verified).orElseThrow();
        assertThat(emailVerificationRepository.findByUserId(verifiedUser.getId())).hasSize(1);
    }

    @Test
    void resendIsLimitedToThreeLinksEveryThirtyMinutesCountingRegistration() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, PASSWORD);

        for (int attempt = 0; attempt < 4; attempt++) {
            resend(email, tenant.getSlug())
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));
        }

        // 1 del registro + 2 reenvios; los otros 2 se omiten en silencio.
        List<EmailMessage> links = emailSender.settledMessagesTo(email);
        assertThat(links).hasSize(3);
        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        List<EmailVerification> verifications = emailVerificationRepository.findByUserId(user.getId());
        assertThat(verifications).hasSize(3);
        assertThat(verifications).filteredOn(verification -> verification.isUsable(Instant.now())).hasSize(1);
        verify(tokenFrom(links.getLast())).andExpect(status().isOk());
    }

    @Test
    void resendRequiresEmailAndStore() throws Exception {
        mockMvc.perform(post(RESEND).contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.tenantSlug").exists());
    }

    // --- Re-registro de una cuenta pendiente ---

    @Test
    void reRegisteringAnExpiredPendingAccountReplacesIt() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, PASSWORD);
        String oldToken = tokenFrom(emailSender.awaitMessageTo(email));
        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        String customerCode = customerRepository.findById(user.getCustomerId()).orElseThrow().getCode();
        expireVerifications(user);

        register(tenant.getSlug(), "Ana Nueva", email, "55551234", OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        String newToken = tokenFrom(emailSender.awaitMessagesTo(email, 2).getLast());
        assertThat(newToken).isNotEqualTo(oldToken);

        // Mismo usuario y cliente (sin duplicados), con los datos y la contrasena del nuevo registro.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ? AND email = ?", Integer.class, tenant.getId(), email))
                .isEqualTo(1);
        User replaced = userRepository.findById(user.getId()).orElseThrow();
        assertThat(replaced.getName()).isEqualTo("Ana Nueva");
        assertThat(replaced.getPhone()).isEqualTo("55551234");
        Customer customer = customerRepository.findById(user.getCustomerId()).orElseThrow();
        assertThat(customer.getName()).isEqualTo("Ana Nueva");
        assertThat(customer.getPhone()).isEqualTo("55551234");
        assertThat(customer.getCode()).isEqualTo(customerCode);
        AuthAccount account = authAccountRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.pending_verification);
        assertThat(passwordEncoder.matches(OTHER_PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(emailVerificationRepository.findByUserId(user.getId()))
                .filteredOn(verification -> verification.getSupersededAt() != null)
                .hasSize(1);

        verify(oldToken).andExpect(status().isBadRequest());
        verify(newToken).andExpect(status().isOk());
        login(email, PASSWORD, tenant.getSlug()).andExpect(status().isUnauthorized());
        login(email, OTHER_PASSWORD, tenant.getSlug()).andExpect(status().isOk());
    }

    @Test
    void afterResendTheLinkIsLiveAgainSoReRegisterLeavesTheAccountUntouched() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, PASSWORD);
        emailSender.awaitMessageTo(email);
        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        expireVerifications(user);
        resend(email, tenant.getSlug()).andExpect(status().isAccepted());
        emailSender.awaitMessagesTo(email, 2);

        register(tenant.getSlug(), "Intruso", email, null, OTHER_PASSWORD).andExpect(status().isAccepted());

        assertThat(emailSender.settledMessagesTo(email)).hasSize(2);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getName()).isEqualTo("Ana");
        assertThat(passwordEncoder.matches(PASSWORD,
                authAccountRepository.findByUserId(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    // --- Utilidades ---

    private void register(String slug, String name, String email, String password) throws Exception {
        register(slug, name, email, null, password).andExpect(status().isAccepted());
    }

    private ResultActions register(String slug, String name, String email, String phone, String password)
            throws Exception {
        String body = """
                {"name": %s, "email": %s, "phone": %s, "password": %s}
                """.formatted(json(name), json(email), json(phone), json(password));
        return mockMvc.perform(post("/api/v1/public/" + slug + "/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions resend(String email, String tenantSlug) throws Exception {
        return mockMvc.perform(post(RESEND)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": %s, \"tenantSlug\": %s}".formatted(json(email), json(tenantSlug))));
    }

    private String resendBody(String email, String tenantSlug) throws Exception {
        return resend(email, tenantSlug)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post(VERIFY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": " + json(token) + "}"));
    }

    private ResultActions login(String email, String password, String tenantSlug) throws Exception {
        return mockMvc.perform(post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": %s, \"password\": %s, \"tenantSlug\": %s}"
                        .formatted(json(email), json(password), json(tenantSlug))));
    }

    private void expireVerifications(User user) {
        jdbcTemplate.update("UPDATE email_verifications SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), user.getId());
    }

    /** Empleado activo de la tienda; devuelve su correo. */
    private String employee(Tenant tenant) {
        String email = uniqueEmail();
        User user = User.builder().name("Empleado").email(email).type(UserType.employee).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(AccountStatus.active)
                .build());
        return email;
    }

    private static String tokenFrom(EmailMessage message) {
        Matcher matcher = VERIFY_LINK.matcher(message.body());
        assertThat(matcher.find()).as("el correo trae el enlace de verificacion").isTrue();
        return matcher.group(1);
    }

    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }

    private Tenant tenantWithCustomerRole() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        Role role = Role.builder()
                .name("Cliente")
                .isSystem(true)
                .permissions(List.of("customer.account.read", "customer.account.update"))
                .build();
        role.setTenantId(tenant.getId());
        roleRepository.save(role);
        return tenant;
    }

    private static String uniqueEmail() {
        return "cliente-" + UUID.randomUUID() + "@test.local";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
