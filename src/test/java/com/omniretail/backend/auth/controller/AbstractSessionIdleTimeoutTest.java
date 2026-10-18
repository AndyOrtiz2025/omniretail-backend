package com.omniretail.backend.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Base de los tests de expiracion por inactividad de sesiones de empleados: cada test inicia sesion de verdad y
 * mueve {@code last_activity_at} en la BD para simular el tiempo sin actividad. Solo utilidades, sin tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AbstractSessionIdleTimeoutTest.ProtectedController.class})
abstract class AbstractSessionIdleTimeoutTest {

    static final String PROTECTED = "/api/v1/test-support/idle-timeout-me";
    static final String PASSWORD = "Correcta123!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AuthAccountRepository authAccountRepository;

    @Autowired
    SessionRepository sessionRepository;

    @Autowired
    AuthAuditLogRepository auditLogRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // --- Utilidades ---

    String loginEmployee() throws Exception {
        User employee = account(tenant(), UserType.employee);
        return token(login(employee.getEmail(), null, null));
    }

    ResultActions call(String token) throws Exception {
        return mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token));
    }

    /** Fija la ultima actividad {@code ago} atras (redondeada a microsegundos, la precision de la columna). */
    Instant setLastActivity(UUID sessionId, Duration ago) {
        Instant at = Instant.now().minus(ago).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("UPDATE sessions SET last_activity_at = ? WHERE id = ?", Timestamp.from(at), sessionId);
        return at;
    }

    Instant lastActivity(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_activity_at FROM sessions WHERE id = ?", Timestamp.class, sessionId).toInstant();
    }

    ResultActions login(String email, String tenantSlug, Boolean rememberMe) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "rememberMe": %s, "tenantSlug": %s}
                        """.formatted(email, PASSWORD, rememberMe,
                        tenantSlug == null ? "null" : "\"" + tenantSlug + "\"")))
                .andExpect(status().isOk());
    }

    static String token(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.token");
    }

    static UUID sessionId(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return UUID.fromString(JsonPath.read(payload, "$.sid"));
    }

    Tenant tenant() {
        return tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    User account(Tenant tenant, UserType type) {
        String email = "idle-" + UUID.randomUUID() + "@test.local";
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

    /** Ruta autenticada sin permisos ni rol: solo valida el JWT y la sesion. */
    @RestController
    static class ProtectedController {

        private final CurrentUser currentUser;

        ProtectedController(CurrentUser currentUser) {
            this.currentUser = currentUser;
        }

        /** ApiPathConfig antepone {@code /api/v1} a todo controller del paquete base. */
        @GetMapping("/test-support/idle-timeout-me")
        public AuthenticatedUser me() {
            return currentUser.require();
        }
    }
}
