package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.AuthAuditLog;
import com.omniretail.backend.auth.entity.AuthExternalIdentity;
import com.omniretail.backend.auth.entity.ExternalIdentityProvider;
import com.omniretail.backend.auth.entity.MfaEnrollment;
import com.omniretail.backend.auth.entity.MfaMethod;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import com.omniretail.backend.auth.repository.AuthExternalIdentityRepository;
import com.omniretail.backend.auth.repository.MfaEnrollmentRepository;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.GoogleJwkSource;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.notification.CapturingEmailSender;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Inicio de sesion con Google. Los ID token se firman con llaves RSA generadas aqui y el backend las
 * recibe por {@link GoogleJwkSource} reemplazado: ningun test llama a Google.
 */
@SpringBootTest(properties = "app.google.client-id=" + GoogleLoginControllerTest.CLIENT_ID)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CapturingEmailSender.Config.class})
@ExtendWith(OutputCaptureExtension.class)
class GoogleLoginControllerTest {

    static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";
    private static final String GOOGLE = "/api/v1/auth/google";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "Correcta12345!";
    private static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    private static final RSAKey GOOGLE_KEY = rsaKey("google-key");
    /** Misma kid que la de Google pero otra llave: firma invalida. */
    private static final RSAKey FORGED_KEY = rsaKey("google-key");

    @TestBean
    private GoogleJwkSource googleJwkSource;

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
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private AuthExternalIdentityRepository identityRepository;

    @Autowired
    private AuthAuditLogRepository auditLogRepository;

    @Autowired
    private MfaEnrollmentRepository enrollmentRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    static GoogleJwkSource googleJwkSource() {
        return new GoogleJwkSource(new ImmutableJWKSet<>(new JWKSet(GOOGLE_KEY.toPublicJWK())));
    }

    // --- Cuenta nueva ---

    @Test
    void newEmailCreatesAnActiveCustomerWithoutVerificationEmail() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        String sub = uniqueSub();

        String body = google(token(sub, email), tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("Ana Google"))
                .andExpect(jsonPath("$.user.type").value("customer"))
                .andExpect(jsonPath("$.user.tenantId").value(tenant.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        assertThat(user.getType()).isEqualTo(UserType.customer);
        assertThat(user.getCustomerId()).isNotNull();
        assertThat(user.getPhone()).isNull();
        AuthAccount account = account(user);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.active);
        AuthExternalIdentity identity = identity(account).orElseThrow();
        assertThat(identity.getSubject()).isEqualTo(sub);
        assertThat(identity.getEmail()).isEqualTo(email);
        assertThat(identity.getTenantId()).isEqualTo(tenant.getId());
        assertThat(identity.getLinkedAt()).isNotNull();
        assertThat(actions(account)).containsExactly("external_identity_linked", "login_success");
        assertThat(emailSender.settledMessagesTo(email)).isEmpty();
        // La sesion sirve para /auth/me.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.token")))
                .andExpect(status().isOk());
        // No tiene contrasena utilizable: el login con contrasena no entra.
        login(email, PASSWORD, tenant.getSlug()).andExpect(status().isUnauthorized());

        // El siguiente login con la misma cuenta de Google entra a la misma cuenta, sin crear otra.
        google(token(sub, email), tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));
        // login_failed: el intento con contrasena de arriba.
        assertThat(actions(account))
                .containsExactly("external_identity_linked", "login_success", "login_failed", "login_success");
    }

    // --- Vinculacion ---

    @Test
    void existingActiveCustomerIsLinkedAndKeepsItsPassword() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);
        String sub = uniqueSub();

        google(token(sub, user.getEmail()), tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));

        AuthAccount account = account(user);
        assertThat(identity(account)).hasValueSatisfying(identity -> assertThat(identity.getSubject()).isEqualTo(sub));
        assertThat(passwordEncoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
        login(user.getEmail(), PASSWORD, tenant.getSlug()).andExpect(status().isOk());
    }

    @Test
    void googleEmailIsMatchedIgnoringCase() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);

        google(token(uniqueSub(), user.getEmail().toUpperCase()), tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));
    }

    @Test
    void pendingAccountIsActivatedButItsPasswordAndSessionsAreInvalidated() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        // Alguien registro el correo con una contrasena suya y nunca lo verifico.
        User user = user(tenant, UserType.customer, AccountStatus.pending_verification);
        String oldHash = account(user).getPasswordHash();
        Session previous = sessionService.open(user, false, "intruso", Instant.now());

        google(token(uniqueSub(), user.getEmail()), tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));

        AuthAccount account = account(user);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.active);
        assertThat(account.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(passwordEncoder.matches(PASSWORD, account.getPasswordHash())).isFalse();
        assertThat(sessionRepository.findById(previous.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(sessionRepository.findByUserIdAndRevokedAtIsNull(user.getId())).hasSize(1); // solo la nueva
        // La contrasena de quien registro la cuenta ya no sirve.
        login(user.getEmail(), PASSWORD, tenant.getSlug()).andExpect(status().isUnauthorized());
        assertThat(auditLogRepository.findByAuthAccountIdOrderByCreatedAtAsc(account.getId()))
                .filteredOn(log -> log.getAction().equals("external_identity_linked"))
                .singleElement()
                .satisfies(log -> assertThat(log.getMetadata()).containsEntry("reason", "pending_verification"));
    }

    @Test
    void anotherGoogleAccountWithTheSameEmailIsRejected() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);
        String first = uniqueSub();
        google(token(first, user.getEmail()), tenant.getSlug()).andExpect(status().isOk());

        expectInvalid(google(token(uniqueSub(), user.getEmail()), tenant.getSlug()));

        assertThat(identity(account(user)))
                .hasValueSatisfying(identity -> assertThat(identity.getSubject()).isEqualTo(first));
        google(token(first, user.getEmail()), tenant.getSlug()).andExpect(status().isOk());
    }

    // --- Token ---

    @Test
    void unverifiedEmailIsRejectedWithoutCreatingOrLinking() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String email = uniqueEmail();
        User existing = user(tenant, UserType.customer, AccountStatus.active);

        expectInvalid(google(token(uniqueSub(), email, claims -> claims.claim("email_verified", false)), tenant.getSlug()));
        expectInvalid(google(token(uniqueSub(), existing.getEmail(), claims -> claims.claim("email_verified", false)),
                tenant.getSlug()));

        assertThat(userRepository.findByTenantIdAndEmail(tenant.getId(), email)).isEmpty();
        assertThat(identity(account(existing))).isEmpty();
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        expectInvalid(google(token(uniqueSub(), uniqueEmail(),
                claims -> claims.audience("otra-app.apps.googleusercontent.com")), tenant.getSlug()));
    }

    @Test
    void wrongIssuerIsRejectedAndBothGoogleIssuersAreAccepted() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        expectInvalid(google(token(uniqueSub(), uniqueEmail(),
                claims -> claims.issuer("https://accounts.example.com")), tenant.getSlug()));

        google(token(uniqueSub(), uniqueEmail(), claims -> claims.issuer("accounts.google.com")), tenant.getSlug())
                .andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        Instant past = Instant.now().minus(Duration.ofHours(2));
        expectInvalid(google(token(uniqueSub(), uniqueEmail(), claims -> claims
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(Duration.ofHours(1))))), tenant.getSlug()));
    }

    @Test
    void invalidSignatureIsRejectedWithoutCountingAFailedAttemptOrLoggingTheToken(CapturedOutput output)
            throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);
        String forged = sign(claims(uniqueSub(), user.getEmail()).build(), FORGED_KEY);

        expectInvalid(google(forged, tenant.getSlug()));
        expectInvalid(google("no-es-un-jwt", tenant.getSlug()));

        AuthAccount account = account(user);
        assertThat(account.getFailedLoginAttempts()).isZero();
        assertThat(actions(account)).isEmpty();
        assertThat(identity(account)).isEmpty();
        assertThat(output.getAll()).doesNotContain(forged);
    }

    @Test
    void validTokenIsNeverLogged(CapturedOutput output) throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        String idToken = token(uniqueSub(), uniqueEmail());

        google(idToken, tenant.getSlug()).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain(idToken);
    }

    // --- Tipo y estado de la cuenta ---

    @Test
    void employeesAreRejected() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User employee = user(tenant, UserType.employee, AccountStatus.active);

        expectInvalid(perform(body(token(uniqueSub(), uniqueEmail()), tenant.getSlug(), "\"employee\"")));
        // Un empleado tampoco entra como cliente con su correo.
        expectInvalid(google(token(uniqueSub(), employee.getEmail()), tenant.getSlug()));
        perform(body(token(uniqueSub(), uniqueEmail()), tenant.getSlug(), "\"customer\"")).andExpect(status().isOk());

        assertThat(identity(account(employee))).isEmpty();
        assertThat(account(employee).getFailedLoginAttempts()).isZero();
    }

    @Test
    void lockedOrDisabledAccountsAreRejected() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User locked = user(tenant, UserType.customer, AccountStatus.temporarily_locked);
        AuthAccount lockedAccount = account(locked);
        lockedAccount.setLockedUntil(Instant.now().plus(Duration.ofMinutes(15)));
        lockedAccount.setFailedLoginAttempts(5);
        authAccountRepository.save(lockedAccount);
        User disabled = user(tenant, UserType.customer, AccountStatus.disabled);

        expectInvalid(google(token(uniqueSub(), locked.getEmail()), tenant.getSlug()));
        expectInvalid(google(token(uniqueSub(), disabled.getEmail()), tenant.getSlug()));

        assertThat(account(locked).getStatus()).isEqualTo(AccountStatus.temporarily_locked);
        assertThat(account(locked).getFailedLoginAttempts()).isEqualTo(5);
        assertThat(account(disabled).getStatus()).isEqualTo(AccountStatus.disabled);
        assertThat(identity(account(locked))).isEmpty();
        assertThat(identity(account(disabled))).isEmpty();
    }

    @Test
    void linkedAccountStillRespectsTheLockout() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);
        String sub = uniqueSub();
        google(token(sub, user.getEmail()), tenant.getSlug()).andExpect(status().isOk());
        AuthAccount account = account(user);
        account.setStatus(AccountStatus.temporarily_locked);
        account.setLockedUntil(Instant.now().plus(Duration.ofMinutes(15)));
        authAccountRepository.save(account);

        expectInvalid(google(token(sub, user.getEmail()), tenant.getSlug()));
    }

    @Test
    void unknownOrMissingTenantIsRejected() throws Exception {
        tenantWithCustomerRole();
        expectInvalid(google(token(uniqueSub(), uniqueEmail()), "no-existe-" + UUID.randomUUID()));
        expectInvalid(google(token(uniqueSub(), uniqueEmail()), null));
    }

    // --- MFA ---

    @Test
    void activeMfaReturnsTheChallengeInsteadOfASession() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);
        MfaEnrollment enrollment = new MfaEnrollment();
        enrollment.setUserId(user.getId());
        enrollment.setMethod(MfaMethod.email);
        enrollment.setEnabled(true);
        enrollment.setVerifiedAt(Instant.now());
        enrollmentRepository.save(enrollment);

        google(token(uniqueSub(), user.getEmail()), tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.method").value("email"))
                .andExpect(jsonPath("$.challengeToken").isNotEmpty())
                .andExpect(jsonPath("$.token").doesNotExist());

        assertThat(sessionRepository.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
        assertThat(actions(account(user))).doesNotContain("login_success");
        assertThat(emailSender.awaitMessageTo(user.getEmail()).subject()).isEqualTo("Tu código de verificación");
    }

    // --- Login normal ---

    @Test
    void passwordLoginIsUnchanged() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        User user = user(tenant, UserType.customer, AccountStatus.active);

        login(user.getEmail(), PASSWORD, tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));
        login(user.getEmail(), "Incorrecta12345!", tenant.getSlug())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(INVALID_CREDENTIALS));
        assertThat(account(user).getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void missingIdTokenIsAValidationError() throws Exception {
        Tenant tenant = tenantWithCustomerRole();
        perform("{\"tenantSlug\": \"%s\"}".formatted(tenant.getSlug()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- Helpers ---

    private void expectInvalid(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.message").value(
                        "No fue posible iniciar sesión. Verifica tus credenciales o intenta más tarde."));
    }

    private ResultActions google(String idToken, String tenantSlug) throws Exception {
        return perform(body(idToken, tenantSlug, "null"));
    }

    private static String body(String idToken, String tenantSlug, String expectedUserType) {
        return "{\"idToken\": \"%s\", \"tenantSlug\": %s, \"expectedUserType\": %s, \"rememberMe\": false}"
                .formatted(idToken, tenantSlug == null ? "null" : "\"" + tenantSlug + "\"", expectedUserType);
    }

    private ResultActions perform(String json) throws Exception {
        return mockMvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions login(String email, String password, String tenantSlug) throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\", \"tenantSlug\": \"%s\"}"
                        .formatted(email, password, tenantSlug)));
    }

    private static String token(String sub, String email) {
        return token(sub, email, claims -> {
        });
    }

    private static String token(String sub, String email, Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder claims = claims(sub, email);
        customizer.accept(claims);
        return sign(claims.build(), GOOGLE_KEY);
    }

    /** Claims como los de un ID token real de Google Identity Services. */
    private static JWTClaimsSet.Builder claims(String sub, String email) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .subject(sub)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Ana Google")
                .claim("picture", "https://lh3.googleusercontent.com/a/foto")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))));
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID())
                    .type(JOSEObjectType.JWT)
                    .build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AuthAccount account(User user) {
        return authAccountRepository.findByUserId(user.getId()).orElseThrow();
    }

    private Optional<AuthExternalIdentity> identity(AuthAccount account) {
        return identityRepository.findByAccountIdAndProvider(account.getId(), ExternalIdentityProvider.google);
    }

    private List<String> actions(AuthAccount account) {
        return auditLogRepository.findByAuthAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .map(AuthAuditLog::getAction)
                .toList();
    }

    private User user(Tenant tenant, UserType type, AccountStatus accountStatus) {
        String email = uniqueEmail();
        User user = User.builder().name("Usuario test").email(email).type(type).status(UserStatus.active).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(accountStatus)
                .build());
        return user;
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
        return "google-" + UUID.randomUUID() + "@test.local";
    }

    private static String uniqueSub() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
    }
}
