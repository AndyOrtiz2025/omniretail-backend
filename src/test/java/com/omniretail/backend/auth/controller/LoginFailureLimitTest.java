package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.service.Totp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Limite de logins fallidos por IP + email con el login real y el bloqueo por cuenta (B-01) real. MockMvc
 * conecta desde 127.0.0.1 (proxy de confianza), asi que cada test elige su IP con {@code X-Forwarded-For}
 * igual que lo haria Caddy. El limitador es un singleton del contexto: cada test usa IP y correo propios.
 */
@SpringBootTest(properties = "app.security.login-failure-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LoginFailureLimitTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "Correcta12345!";
    private static final String WRONG = "Incorrecta12345!";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void theSixthAttemptAfterFiveFailuresIsRateLimitedWithTheStandardBodyAndRetryAfter() throws Exception {
        String ip = nextIp();
        User user = employee();
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());
        }

        MvcResult blocked = login(ip, user.getEmail(), PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value(LOGIN))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        Number bodyRetryAfter = JsonPath.read(blocked.getResponse().getContentAsString(), "$.retryAfterSeconds");
        assertThat(retryAfter).isPositive().isLessThanOrEqualTo(900).isEqualTo(bodyRetryAfter.longValue());
        // B-01 sigue igual: el 5.o fallo bloqueo la cuenta 15 minutos.
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.temporarily_locked);
    }

    @Test
    void anUnknownEmailIsLimitedExactlyTheSameWay() throws Exception {
        String ip = nextIp();
        String email = "nadie-" + UUID.randomUUID() + "@test.local";
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(ip, email, WRONG).andExpect(status().isUnauthorized());
        }

        login(ip, email, WRONG).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void theEmailIsNormalizedBeforeCounting() throws Exception {
        String ip = nextIp();
        String email = "mixto-" + UUID.randomUUID() + "@test.local";
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(ip, attempt % 2 == 0 ? email.toUpperCase() : "  " + email + " ", WRONG)
                    .andExpect(status().isUnauthorized());
        }

        login(ip, email, WRONG).andExpect(status().isTooManyRequests());
    }

    @Test
    void anotherIpWithTheSameEmailAndTheSameIpWithAnotherEmailAreNotLimited() throws Exception {
        String ip = nextIp();
        String email = "nadie-" + UUID.randomUUID() + "@test.local";
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(ip, email, WRONG).andExpect(status().isUnauthorized());
        }

        login(nextIp(), email, WRONG).andExpect(status().isUnauthorized());
        login(ip, "otro-" + UUID.randomUUID() + "@test.local", WRONG).andExpect(status().isUnauthorized());
    }

    @Test
    void aDeliveredSessionClearsTheCounter() throws Exception {
        String ip = nextIp();
        User user = employee();
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());
        }
        login(ip, user.getEmail(), PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());

        // Sin la limpieza, el 2.o de estos ya seria el 6.o intento tras 5 fallos.
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());
        }
        assertThat(account(user).getStatus()).isEqualTo(AccountStatus.active);
    }

    @Test
    void openingTheMfaChallengeNeitherCountsNorClearsTheCounter() throws Exception {
        String ip = nextIp();
        User user = employee();
        enableMfa(ip, user);
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());
        }

        login(ip, user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true));
        login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());

        login(ip, user.getEmail(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void theSessionDeliveredByMfaVerifyClearsTheCounter() throws Exception {
        String ip = nextIp();
        User user = employee();
        String recoveryCode = enableMfa(ip, user);
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());
        }
        String challenge = JsonPath.read(login(ip, user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.challengeToken");

        mockMvc.perform(post("/api/v1/auth/mfa/verify").header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\": \"%s\", \"code\": \"%s\"}".formatted(challenge, recoveryCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Sin la limpieza, el 2.o de estos ya seria el 6.o intento tras 5 fallos.
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(ip, user.getEmail(), WRONG).andExpect(status().isUnauthorized());
        }
    }

    /** Activa TOTP tras un login sin MFA (que tambien limpia el contador) y devuelve un codigo de recuperacion. */
    private String enableMfa(String ip, User user) throws Exception {
        String token = JsonPath.read(login(ip, user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
        String begin = mockMvc.perform(post("/api/v1/auth/mfa/enrollment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"totp\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        byte[] secret = Totp.base32Decode(JsonPath.read(begin, "$.secret"));
        String codes = mockMvc.perform(post("/api/v1/auth/mfa/enrollment/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(Totp.generate(secret, Totp.step(Instant.now())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(codes, "$.recoveryCodes[0]");
    }

    private ResultActions login(String ip, String email, String password) throws Exception {
        return mockMvc.perform(post(LOGIN).header("X-Forwarded-For", ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
    }

    private static String nextIp() {
        int n = NEXT_IP.getAndIncrement();
        return "198.51.100." + n;
    }

    private AuthAccount account(User user) {
        return authAccountRepository.findByUserId(user.getId()).orElseThrow();
    }

    private User employee() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        String email = "limit-" + UUID.randomUUID() + "@test.local";
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
}
