package com.omniretail.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.AuthAuditLog;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.AuthAuditService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Expiracion por inactividad en el servidor de las sesiones de empleados, con el limite por defecto (60 min). */
class SessionIdleTimeoutTest extends AbstractSessionIdleTimeoutTest {

    @Test
    void employeeActiveFiftyNineMinutesAgoPassesAndTheSessionIsRenewed() throws Exception {
        String token = loginEmployee();
        UUID sessionId = sessionId(token);
        setLastActivity(sessionId, Duration.ofMinutes(59));

        call(token).andExpect(status().isOk());

        Session session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getLastActivityAt()).isCloseTo(Instant.now(), within(10, ChronoUnit.SECONDS));
    }

    @Test
    void employeeSixtyOneMinutesWithoutActivityGets401AndTheSessionIsRevokedAndAudited() throws Exception {
        String token = loginEmployee();
        UUID sessionId = sessionId(token);
        setLastActivity(sessionId, Duration.ofMinutes(61));

        call(token).andExpect(status().isUnauthorized());

        Session session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();
        List<AuthAuditLog> idle = auditLogRepository.findAll().stream()
                .filter(log -> AuthAuditService.SESSION_IDLE_EXPIRED.equals(log.getAction()))
                .filter(log -> sessionId.toString().equals(log.getMetadata().get("sessionId")))
                .toList();
        assertThat(idle).hasSize(1);
        assertThat(idle.getFirst().getActorUserId()).isEqualTo(session.getUserId());
        assertThat(idle.getFirst().getMetadata()).containsEntry("idleTimeoutMinutes", 60);
        // Solo identificadores: ni token ni correo en la auditoria.
        assertThat(idle.getFirst().getMetadata().toString()).doesNotContain(token).doesNotContain("@");

        // Revocada: aunque vuelva a haber "actividad", el mismo token ya no sirve.
        setLastActivity(sessionId, Duration.ZERO);
        call(token).andExpect(status().isUnauthorized());
    }

    @Test
    void activityKeepsTheSessionAliveBeyondTheIdleLimit() throws Exception {
        String token = loginEmployee();
        UUID sessionId = sessionId(token);

        // Uso continuo: cada 50 min un request. Sumado pasa de 60 min, pero nunca 60 seguidos sin actividad.
        for (int round = 0; round < 3; round++) {
            setLastActivity(sessionId, Duration.ofMinutes(50));
            call(token).andExpect(status().isOk());
        }

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void activityIsWrittenAtMostOncePerMinute() throws Exception {
        String token = loginEmployee();
        UUID sessionId = sessionId(token);

        Instant thirtySecondsAgo = setLastActivity(sessionId, Duration.ofSeconds(30));
        call(token).andExpect(status().isOk());
        call(token).andExpect(status().isOk());
        assertThat(lastActivity(sessionId)).isEqualTo(thirtySecondsAgo);

        setLastActivity(sessionId, Duration.ofSeconds(61));
        call(token).andExpect(status().isOk());
        assertThat(lastActivity(sessionId)).isCloseTo(Instant.now(), within(10, ChronoUnit.SECONDS));
    }

    @Test
    void customersHaveNoIdleExpirationAndWriteNothing() throws Exception {
        Tenant store = tenant();
        User customer = account(store, UserType.customer);
        String token = token(login(customer.getEmail(), store.getSlug(), true));
        UUID sessionId = sessionId(token);
        Instant fiveHoursAgo = setLastActivity(sessionId, Duration.ofHours(5));

        call(token).andExpect(status().isOk());

        Session session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getLastActivityAt()).isEqualTo(fiveHoursAgo);
        // "Recordarme" sigue siendo de 30 dias.
        assertThat(session.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void employeeAbsoluteEightHourLimitIsKept() throws Exception {
        String token = loginEmployee();
        Session session = sessionRepository.findById(sessionId(token)).orElseThrow();

        assertThat(session.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofHours(8)), within(1, ChronoUnit.MINUTES));
        jdbcTemplate.update("UPDATE sessions SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), session.getId());
        call(token).andExpect(status().isUnauthorized());
    }
}
