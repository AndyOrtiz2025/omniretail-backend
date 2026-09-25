package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** En perfil test no se carga la semilla: cada test crea sus propios tenants, usuarios y cuentas. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AuthControllerTest.WhoAmIController.class})
class AuthControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String PROTECTED = "/api/v1/test-support/auth-me";
    private static final String PASSWORD = "Correcta123!";
    private static final String GENERIC_MESSAGE =
            "No fue posible iniciar sesión. Verifica tus credenciales o intenta más tarde.";

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
    private PasswordEncoder passwordEncoder;

    @Test
    void employeeLoginReturnsTokenUsableOnProtectedRoute() throws Exception {
        Tenant tenant = tenant();
        User employee = account(tenant, UserType.employee, uniqueEmail());

        String response = login(employee.getEmail(), PASSWORD, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(employee.getId().toString()))
                .andExpect(jsonPath("$.user.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.user.type").value("employee"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(JsonPath.read(response, "$.token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()));
        AuthAccount account = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
        assertThat(account.getLastLoginAt()).isNotNull();
    }

    @Test
    void emailIsNormalizedBeforeLookup() throws Exception {
        User employee = account(tenant(), UserType.employee, "admin@ferrepharma.demo");

        login("  Admin@Ferrepharma.Demo  ", PASSWORD, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(employee.getId().toString()));
    }

    @Test
    void wrongPasswordWithSeveralCandidatesDoesNotTouchAnyAccount() throws Exception {
        String email = uniqueEmail();
        User first = account(tenant(), UserType.employee, email);
        User second = account(tenant(), UserType.employee, email);

        login(email, "Incorrecta1!", null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        assertThat(authAccountRepository.findByUserId(first.getId()).orElseThrow().getFailedLoginAttempts()).isZero();
        assertThat(authAccountRepository.findByUserId(second.getId()).orElseThrow().getFailedLoginAttempts()).isZero();
    }

    @Test
    void wrongPasswordWithSingleCandidateCountsTheFailure() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail());

        login(employee.getEmail(), "Incorrecta1!", null, null).andExpect(status().isUnauthorized());

        assertThat(authAccountRepository.findByUserId(employee.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnSameBody() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail());

        String wrongPassword = login(employee.getEmail(), "Incorrecta1!", null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail(), "Incorrecta1!", null, null)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutTimestamp(unknownEmail)).isEqualTo(withoutTimestamp(wrongPassword));
    }

    @Test
    void fiveFailuresLockTheAccount() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail());

        for (int i = 0; i < 5; i++) {
            login(employee.getEmail(), "Incorrecta1!", null, null).andExpect(status().isUnauthorized());
        }
        login(employee.getEmail(), PASSWORD, null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        AuthAccount account = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.temporarily_locked);
        assertThat(account.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(account.getLockedUntil()).isCloseTo(Instant.now().plus(15, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void expiredLockAllowsLoginAndResetsCounters() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail());
        AuthAccount account = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
        account.setStatus(AccountStatus.temporarily_locked);
        account.setFailedLoginAttempts(5);
        account.setLockedUntil(Instant.now().minus(1, ChronoUnit.MINUTES));
        authAccountRepository.save(account);

        login(employee.getEmail(), PASSWORD, null, null).andExpect(status().isOk());

        AuthAccount reloaded = authAccountRepository.findByUserId(employee.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.active);
        assertThat(reloaded.getFailedLoginAttempts()).isZero();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void customerNeedsTheTenantSlug() throws Exception {
        Tenant tenant = tenant();
        User customer = account(tenant, UserType.customer, uniqueEmail());

        login(customer.getEmail(), PASSWORD, null, null).andExpect(status().isUnauthorized());
        login(customer.getEmail(), PASSWORD, null, "tienda-que-no-existe").andExpect(status().isUnauthorized());

        String response = login(customer.getEmail(), PASSWORD, true, tenant.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.tenantId").value(tenant.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertSessionLasts(response, Duration.ofDays(30));
    }

    @Test
    void sameCustomerEmailInTwoStoresEntersTheRequestedStore() throws Exception {
        String email = uniqueEmail();
        Tenant storeA = tenant();
        Tenant storeB = tenant();
        account(storeA, UserType.customer, email);
        User customerB = account(storeB, UserType.customer, email);

        login(email, PASSWORD, null, storeB.getSlug())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(customerB.getId().toString()))
                .andExpect(jsonPath("$.user.tenantId").value(storeB.getId().toString()));
    }

    @Test
    void employeeRememberMeIsIgnored() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail());

        String response = login(employee.getEmail(), PASSWORD, true, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertSessionLasts(response, Duration.ofHours(8));
        assertThat(session(response).getRememberMe()).isFalse();
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        User employee = account(tenant(), UserType.employee, uniqueEmail());
        String response = login(employee.getEmail(), PASSWORD, null, null).andReturn().getResponse().getContentAsString();
        String token = bearer(JsonPath.read(response, "$.token"));

        mockMvc.perform(post(LOGOUT)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(LOGOUT).header("Authorization", token)).andExpect(status().isNoContent());

        mockMvc.perform(get(PROTECTED).header("Authorization", token)).andExpect(status().isUnauthorized());
        assertThat(session(response).getRevokedAt()).isNotNull();
    }

    private ResultActions login(String email, String password, Boolean rememberMe, String tenantSlug) throws Exception {
        String body = """
                {"email": "%s", "password": "%s", "rememberMe": %s, "tenantSlug": %s}
                """.formatted(email, password, rememberMe, tenantSlug == null ? "null" : "\"" + tenantSlug + "\"");
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body));
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

    private User account(Tenant tenant, UserType type, String email) {
        User user = User.builder().name("Usuario test").email(email).type(type).build();
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

    private Session session(String loginResponse) {
        String token = JsonPath.read(loginResponse, "$.token");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return sessionRepository.findById(UUID.fromString(JsonPath.read(payload, "$.sid"))).orElseThrow();
    }

    private void assertSessionLasts(String loginResponse, Duration ttl) {
        assertThat(session(loginResponse).getExpiresAt())
                .isCloseTo(Instant.now().plus(ttl), within(1, ChronoUnit.MINUTES));
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }

    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @RestController
    static class WhoAmIController {

        private final CurrentUser currentUser;

        WhoAmIController(CurrentUser currentUser) {
            this.currentUser = currentUser;
        }

        /** ApiPathConfig antepone {@code /api/v1} a todo controller del paquete base. */
        @GetMapping("/test-support/auth-me")
        public AuthenticatedUser me() {
            return currentUser.require();
        }
    }
}
