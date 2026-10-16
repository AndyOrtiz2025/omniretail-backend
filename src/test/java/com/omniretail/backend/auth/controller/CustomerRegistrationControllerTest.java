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
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
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
    private static final String ACCEPTED_MESSAGE = "Si el correo es válido, te enviamos un mensaje para continuar.";
    private static final String PASSWORD = "Segura123!";
    private static final String OTHER_PASSWORD = "OtraClave456#";
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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE))
                .andExpect(jsonPath("$.user.name").value("Ana Pérez"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.type").value("customer"))
                .andExpect(jsonPath("$.user.id").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        UUID userId = user.getId();
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

        EmailMessage mail = emailSender.awaitMessageTo(email);
        String token = tokenFrom(mail);
        String link = "http://localhost:3000/verificar-correo/" + token;
        assertThat(mail.body()).contains("Hola Ana Pérez:", link, "vence en 30 minutos");
        assertThat(mail.html()).contains("Ana Pérez", tenant.getName(), "href=\"" + link + "\"",
                ">" + link + "</a>", "Verificar mi correo", "vence en 30 minutos");
        assertThat(verification.getTokenHash()).hasSize(64).isNotEqualTo(token);
        assertThat(response).doesNotContain(token).doesNotContain(verification.getTokenHash());
        assertThat(response).doesNotContain("token");
    }

    @Test
    void customerCodesAdvancePerTenant() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        register(tenant.getSlug(), "Uno", uniqueEmail(), null, PASSWORD).andExpect(status().isAccepted());
        String second = uniqueEmail();
        register(tenant.getSlug(), "Dos", second, null, PASSWORD).andExpect(status().isAccepted());

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), second).orElseThrow();
        assertThat(customerRepository.findById(user.getCustomerId()).orElseThrow().getCode()).isEqualTo("CLI-002");
    }

    @Test
    void newAndExistingEmailGetTheSameResponse() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String existing = verifiedCustomer(tenant, "Ana");
        String fresh = uniqueEmail();

        String forExisting = register(tenant.getSlug(), "Ana", existing.toUpperCase(), null, OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String forFresh = register(tenant.getSlug(), "Ana", fresh, null, OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Solo cambia el correo que cada uno envio: ni codigo, ni mensaje, ni campos distintos.
        assertThat(forExisting.replace(existing, "<correo>")).isEqualTo(forFresh.replace(fresh, "<correo>"));
        assertThat(forExisting).doesNotContain("EMAIL_ALREADY_REGISTERED").doesNotContain("\"id\"");
    }

    @Test
    void existingVerifiedCustomerGetsAccountExistsEmailAndNothingChanges() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = verifiedCustomer(tenant, "Ana");
        User before = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        String hashBefore = authAccountRepository.findByUserId(before.getId()).orElseThrow().getPasswordHash();

        register(tenant.getSlug(), "Intruso", email, "55550000", OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        // 1 verificacion del alta + 1 aviso.
        EmailMessage notice = emailSender.awaitMessagesTo(email, 2).getLast();
        assertThat(notice.purpose()).isEqualTo(EmailPurpose.ACCOUNT_ALREADY_EXISTS);
        assertThat(notice.subject()).isEqualTo("Ya tienes una cuenta");
        String loginLink = "http://localhost:3000/tienda/" + tenant.getSlug() + "/iniciar-sesion";
        assertThat(notice.body()).contains("Hola Ana:", "ya tienes una", loginLink, "¿Olvidaste tu contraseña?");
        assertThat(notice.html()).contains("href=\"" + loginLink + "\"", "Iniciar sesión", tenant.getName());
        assertThat(notice.body()).doesNotContain("verificar-correo").doesNotContain("restablecer-contrasena");

        User after = userRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("Ana");
        assertThat(after.getPhone()).isNull();
        AuthAccount account = authAccountRepository.findByUserId(before.getId()).orElseThrow();
        assertThat(account.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.active);
        assertThat(emailVerificationRepository.findByUserId(before.getId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ? AND lower(email) = ?", Integer.class,
                tenant.getId(), email)).isEqualTo(1);
    }

    @Test
    void accountExistsEmailIsLimitedToThreeEveryThirtyMinutes() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = verifiedCustomer(tenant, "Ana");

        for (int attempt = 0; attempt < 5; attempt++) {
            register(tenant.getSlug(), "Ana", email, null, OTHER_PASSWORD).andExpect(status().isAccepted());
        }

        // 1 verificacion del alta + solo 3 avisos.
        assertThat(emailSender.settledMessagesTo(email))
                .filteredOn(message -> message.purpose() == EmailPurpose.ACCOUNT_ALREADY_EXISTS)
                .hasSize(3);
    }

    @Test
    void employeeOrDisabledAccountEmailGetsSameResponseAndNoEmail() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String employeeEmail = uniqueEmail();
        User employee = User.builder().name("Empleado").email(employeeEmail).type(UserType.employee).build();
        employee.setTenantId(tenant.getId());
        employee = userRepository.save(employee);
        authAccountRepository.save(AuthAccount.builder()
                .userId(employee.getId())
                .email(employeeEmail)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(AccountStatus.active)
                .build());

        String disabledEmail = verifiedCustomer(tenant, "Beto");
        User disabledUser = userRepository.findByTenantIdAndEmail(tenant.getId(), disabledEmail).orElseThrow();
        AuthAccount disabled = authAccountRepository.findByUserId(disabledUser.getId()).orElseThrow();
        disabled.setStatus(AccountStatus.disabled);
        authAccountRepository.save(disabled);

        register(tenant.getSlug(), "Ana", employeeEmail, null, OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));
        register(tenant.getSlug(), "Beto", disabledEmail, null, OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        assertThat(emailSender.settledMessagesTo(employeeEmail)).isEmpty();
        // Solo la verificacion del alta, ningun aviso.
        assertThat(emailSender.settledMessagesTo(disabledEmail)).hasSize(1);
        assertThat(authAccountRepository.findByUserId(disabledUser.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.disabled);
    }

    @Test
    void pendingAccountWithLiveLinkIsLeftUntouched() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
        emailSender.awaitMessageTo(email);
        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        String hashBefore = authAccountRepository.findByUserId(user.getId()).orElseThrow().getPasswordHash();

        register(tenant.getSlug(), "Intruso", email, null, OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        assertThat(emailSender.settledMessagesTo(email)).hasSize(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getName()).isEqualTo("Ana");
        assertThat(authAccountRepository.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
        assertThat(emailVerificationRepository.findByUserId(user.getId())).hasSize(1);
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

            // Todos reciben la misma respuesta, gane o pierda la carrera.
            assertThat(statuses).containsOnly(202);
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
        register(tenantWithCustomerRole().getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
        register(tenantWithCustomerRole().getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
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
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());

        login(email, tenant.getSlug()).andExpect(status().isUnauthorized());
    }

    // --- Verificacion ---

    @Test
    void validTokenActivatesAccountAndLoginWorks() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
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
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
        String token = tokenFrom(emailSender.awaitMessageTo(email));
        verify(token).andExpect(status().isOk());

        String expiredEmail = uniqueEmail();
        register(tenant.getSlug(), "Beto", expiredEmail, null, PASSWORD).andExpect(status().isAccepted());
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
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
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
        register(tenant.getSlug(), "Ana", email, null, PASSWORD).andExpect(status().isAccepted());
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

    /** Registra y verifica un cliente; devuelve su correo. */
    private String verifiedCustomer(Tenant tenant, String name) throws Exception {
        String email = uniqueEmail();
        register(tenant.getSlug(), name, email, null, PASSWORD).andExpect(status().isAccepted());
        verify(tokenFrom(emailSender.awaitMessageTo(email))).andExpect(status().isOk());
        return email;
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
