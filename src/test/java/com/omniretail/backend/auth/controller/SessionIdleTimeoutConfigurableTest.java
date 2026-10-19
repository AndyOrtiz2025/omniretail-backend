package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.auth.service.AuthSessionProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * El limite sale de {@code EMPLOYEE_IDLE_TIMEOUT_MINUTES} (aqui 5 min, en su propio contexto). Reutiliza las
 * utilidades de {@link AbstractSessionIdleTimeoutTest}.
 */
@TestPropertySource(properties = "EMPLOYEE_IDLE_TIMEOUT_MINUTES=5")
class SessionIdleTimeoutConfigurableTest extends AbstractSessionIdleTimeoutTest {

    @Test
    void theIdleLimitComesFromTheEnvironmentVariable() throws Exception {
        String active = loginEmployee();
        setLastActivity(sessionId(active), Duration.ofMinutes(4));
        call(active).andExpect(status().isOk());

        String idle = loginEmployee();
        UUID idleSession = sessionId(idle);
        setLastActivity(idleSession, Duration.ofMinutes(6));
        call(idle).andExpect(status().isUnauthorized());
    }

    @Test
    void aZeroOrNegativeLimitDoesNotStart() {
        assertThatThrownBy(() -> new AuthSessionProperties(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthSessionProperties(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthSessionProperties(null)).isInstanceOf(IllegalStateException.class);
    }
}
