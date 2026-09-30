package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.notification.EmailMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class CustomerRegistrationControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String VERIFY = "/api/v1/auth/email/verify";
    private static final String PASSWORD = "Segura123!";
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

    // --- Registro ---

    @Test
    void registerCreatesPendingCustomerAndSendsVerificationEmail() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();

        String response = register(tenant.getSlug(), "  Ana Pérez ", "  " + email.toUpperCase() + " ", "55551234", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.name").value("Ana Pérez"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.type").value("customer"))
                .andReturn().getResponse().getContentAsString();

        UUID userId = UUID.fromString(JsonPath.read(response, "$.user.id"));
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getTenantId()).isEqualTo(tenant.getId());
        assertThat(user.getType()).isEqualTo(UserType.customer);
        assertThat(user.getPhone()).isEqualTo("55551234");
        Role role = roleRepository.findById(user.getRoleId()).orElseThrow();
        assertThat(role.getPermissions()).contains("customer.account.read");

        Customer customer = customerRepository.findById(user.getCustomerId()).orElseThrow();
        assertThat(customer.getUserId()).isEqualTo(userId);
        assertThat(customer.getTenantId()).isEqualTo(tenant.getId());
        assertThat(customer.getCode()).isEqualTo("CLI-001");
        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.active);
        assertThat(customer.getEmail()).isEqualTo(email);

        AuthAccount account = authAccountRepository.findByUserId(userId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.pending_verification);
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(passwordEncoder.matches(PASSWORD, account.getPasswordHash())).isTrue();

        List<EmailVerification> verifications = emailVerificationRepository.findByUserId(userId);
        assertThat(verifications).hasSize(1);
        EmailVerification verification = verifications.getFirst();
        assertThat(verification.getVerifiedAt()).isNull();
        assertThat(verification.getExpiresAt()).isBetween(
                verification.getCreatedAt().plusSeconds(29 * 60), verification.getCreatedAt().plusSeconds(31 * 60));

        String token = tokenFrom(emailSender.awaitMessageTo(email));
        assertThat(verification.getTokenHash()).hasSize(64).isNotEqualTo(token);
        assertThat(response).doesNotContain(token).doesNotContain(verification.getTokenHash());
        assertThat(response).doesNotContain("token");
    }

    @Test
    void customerCodesAdvancePerTenant() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        register(tenant.getSlug(), "Uno", uniqueEmail(), null, PASSWORD).andExpect(status().isCreated());
        String second = register(tenant.getSlug(), "Dos", uniqueEmail(), null, PASSWORD)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        User user = userRepository.findById(UUID.fromString(JsonPath.read(second, "$.user.id"))).orElseThrow();
        assertThat(customerRepository.findById(user.getCustomerId()).orElseThrow().getCode()).isEqualTo("CLI-002");
    }

    @Test
    void duplicateEmailInSameTenantReturnsConflict() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());

        register(tenant.getSlug(), "Otra Ana", email.toUpperCase(), null, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.message")
                        .value("Ya existe una cuenta con este correo. Inicia sesión o recupera tu contraseña."));
    }

    @Test
    void concurrentRegistrationsWithSameEmailCreateOnlyOneAccount() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        int attempts = 4;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return register(tenant.getSlug(), "Ana", email, null, PASSWORD).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(code -> code == 201).hasSize(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ? AND email = ?", Integer.class, tenant.getId(), email))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customers WHERE tenant_id = ?", Integer.class, tenant.getId()))
                .isEqualTo(1);
    }

    @Test
    void sameEmailInAnotherTenantIsAllowed() throws Exception {
        String email = uniqueEmail();
        register(tenantWithCustomerRole().getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());
        register(tenantWithCustomerRole().getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());
    }

    @Test
    void unknownOrInactiveStoreReturnsGenericError() throws Exception {
        Tenant inactive = tenantWithCustomerRole();
        inactive.setStatus(TenantStatus.inactive);
        tenantRepository.save(inactive);

        for (String slug : List.of("no-existe-" + UUID.randomUUID(), inactive.getSlug())) {
            register(slug, "Ana", uniqueEmail(), null, PASSWORD)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("No se pudo completar el registro."));
        }
    }

    @Test
    void storeWithoutCustomerRoleReturnsInternalError() throws Exception {
        Tenant tenant = tenant();
        String email = uniqueEmail();

        register(tenant.getSlug(), "Ana", email, null, PASSWORD)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("No se pudo completar el registro."));
        assertThat(userRepository.existsByTenantIdAndEmail(tenant.getId(), email)).isFalse();
    }

    @Test
    void invalidFieldsReturnValidationErrorWithFrontendMessages() throws Exception {
        Tenant tenant = tenantWithCustomerRole();

        register(tenant.getSlug(), " ", "correo-invalido", "5555-123", "Abcdefg1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.name").value("El nombre es obligatorio."))
                .andExpect(jsonPath("$.fields.email").value("Ingrese un correo con formato válido."))
                .andExpect(jsonPath("$.fields.phone").value("El teléfono solo puede contener números."))
                .andExpect(jsonPath("$.fields.password").value(
                        "La contraseña debe tener entre 8 y 24 caracteres e incluir una mayúscula, una minúscula, "
                                + "un número y un carácter especial."));

        register(tenant.getSlug(), "Ana", "", "5555123", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").value("El correo es obligatorio."))
                .andExpect(jsonPath("$.fields.phone").value("El teléfono debe tener exactamente 8 dígitos."))
                .andExpect(jsonPath("$.fields.password").value("La contraseña es obligatoria."));
    }

    @Test
    void passwordEqualToEmailIsRejected() throws Exception {
        String email = "Ana1!x@test.local";
        register(tenantWithCustomerRole().getSlug(), "Ana", email, null, email.toLowerCase())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").value("La contraseña no puede ser igual al correo electrónico."));
    }

    @Test
    void loginBeforeVerificationFails() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());

        login(email, tenant.getSlug()).andExpect(status().isUnauthorized());
    }

    // --- Verificacion ---

    @Test
    void validTokenActivatesAccountAndLoginWorks() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());
        String token = tokenFrom(emailSender.awaitMessageTo(email));

        verify(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSlug").value(tenant.getSlug()));

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        assertThat(authAccountRepository.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.active);
        assertThat(emailVerificationRepository.findByUserId(user.getId()).getFirst().getVerifiedAt()).isNotNull();
        login(email, tenant.getSlug()).andExpect(status().isOk());
    }

    @Test
    void usedExpiredOrUnknownTokenReturnsSameGenericError() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());
        String token = tokenFrom(emailSender.awaitMessageTo(email));
        verify(token).andExpect(status().isOk());

        String expiredEmail = uniqueEmail();
        register(tenant.getSlug(), "Beto", expiredEmail, null, PASSWORD).andExpect(status().isCreated());
        String expiredToken = tokenFrom(emailSender.awaitMessageTo(expiredEmail));
        User expiredUser = userRepository.findByTenantIdAndEmail(tenant.getId(), expiredEmail).orElseThrow();
        jdbcTemplate.update("UPDATE email_verifications SET expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), expiredUser.getId());

        for (String invalid : List.of(token, expiredToken, "token-que-no-existe")) {
            verify(invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"))
                    .andExpect(jsonPath("$.message").value("Este enlace no es válido o ya expiró."));
        }
        assertThat(authAccountRepository.findByUserId(expiredUser.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.pending_verification);
    }

    @Test
    void verificationNeverReactivatesDisabledAccount() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());
        String token = tokenFrom(emailSender.awaitMessageTo(email));
        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        AuthAccount account = authAccountRepository.findByUserId(user.getId()).orElseThrow();
        account.setStatus(AccountStatus.disabled);
        authAccountRepository.save(account);

        verify(token).andExpect(status().isOk());

        assertThat(authAccountRepository.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.disabled);
        login(email, tenant.getSlug()).andExpect(status().isUnauthorized());
    }

    @Test
    void noPlainTokenIsStoredInDatabase() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isCreated());
        String token = tokenFrom(emailSender.awaitMessageTo(email));

        Integer matches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verifications WHERE token_hash = ? OR token_hash LIKE ?",
                Integer.class, token, "%" + token + "%");
        assertThat(matches).isZero();
    }

    // --- Utilidades ---

    private ResultActions register(String slug, String name, String email, String phone, String password) throws Exception {
        String body = """
                {"name": %s, "email": %s, "phone": %s, "password": %s}
                """.formatted(json(name), json(email), json(phone), json(password));
        return mockMvc.perform(post("/api/v1/public/" + slug + "/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post(VERIFY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": " + json(token) + "}"));
    }

    private ResultActions login(String email, String tenantSlug) throws Exception {
        return mockMvc.perform(post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": %s, \"password\": %s, \"tenantSlug\": %s}"
                        .formatted(json(email), json(PASSWORD), json(tenantSlug))));
    }

    private static String tokenFrom(EmailMessage message) {
        Matcher matcher = VERIFY_LINK.matcher(message.body());
        assertThat(matcher.find()).as("el correo trae el enlace de verificacion").isTrue();
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

    private Tenant tenantWithCustomerRole() {
        Tenant tenant = tenant();
        // Rol operativo de sistema: nunca debe elegirse para un cliente.
        role(tenant, "Cajero", List.of("pos.sales.create", "customer.account.read"));
        role(tenant, "Cliente", List.of("customer.account.read", "customer.account.update"));
        return tenant;
    }

    private void role(Tenant tenant, String name, List<String> permissions) {
        Role role = Role.builder().name(name).isSystem(true).permissions(permissions).build();
        role.setTenantId(tenant.getId());
        roleRepository.save(role);
    }

    private static String uniqueEmail() {
        return "cliente-" + UUID.randomUUID() + "@test.local";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
