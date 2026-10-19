package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.repository.SessionWithOwner;
import com.omniretail.backend.shared.security.SessionRevoker;
import com.omniretail.backend.shared.security.SessionValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionService implements SessionValidator, SessionRevoker {

    static final Duration EMPLOYEE_TTL = Duration.ofHours(8);
    static final Duration CUSTOMER_TTL = Duration.ofHours(2);
    static final Duration CUSTOMER_REMEMBER_ME_TTL = Duration.ofDays(30);
    /** Como mucho una escritura de {@code last_activity_at} por sesion en este intervalo. */
    static final Duration ACTIVITY_WRITE_INTERVAL = Duration.ofMinutes(1);

    private final SessionRepository sessionRepository;
    private final AuthAuditService auditService;
    private final AuthSessionProperties sessionProperties;

    /** Empleados: siempre 8 h y sin "recordarme". Clientes: 2 h, o 30 dias con "recordarme". */
    @Transactional
    public Session open(User user, Boolean rememberMe, String deviceLabel, Instant now) {
        boolean employee = user.getType() == UserType.employee;
        boolean remember = !employee && Boolean.TRUE.equals(rememberMe);
        Duration ttl = employee ? EMPLOYEE_TTL : remember ? CUSTOMER_REMEMBER_ME_TTL : CUSTOMER_TTL;
        Session session = Session.builder()
                .userId(user.getId())
                .activeBranchId(user.getBranchId())
                .deviceLabel(deviceLabel)
                .rememberMe(remember)
                // El claim exp del JWT va en segundos: se trunca para que coincida con la sesion.
                .expiresAt(now.plus(ttl).truncatedTo(ChronoUnit.SECONDS))
                .build();
        return sessionRepository.save(session);
    }

    /** Idempotente: si la sesion ya estaba revocada no cambia nada. */
    @Transactional
    public void revoke(UUID sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> session.setRevokedAt(Instant.now()));
    }

    @Override
    @Transactional
    public void revokeAllSessions(UUID userId) {
        Instant now = Instant.now();
        sessionRepository.findByUserIdAndRevokedAtIsNull(userId).forEach(session -> session.setRevokedAt(now));
    }

    /** Revoca todas las sesiones activas de {@code userId} salvo {@code keptSessionId} (la de quien hace el cambio). */
    @Transactional
    public void revokeOtherSessions(UUID userId, UUID keptSessionId) {
        Instant now = Instant.now();
        sessionRepository.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(session -> !session.getId().equals(keptSessionId))
                .forEach(session -> session.setRevokedAt(now));
    }

    /**
     * Ademas de existir, ser del usuario, no estar revocada y no haber vencido (8 h / 2 h / 30 dias), la sesion
     * de un empleado no debe llevar mas de {@code app.auth.employee-idle-timeout} sin actividad. Si la supera se
     * revoca (y se audita) y el request responde 401. Cada request valido de un empleado renueva su actividad
     * (ventana deslizante), escribiendo como mucho una vez por {@link #ACTIVITY_WRITE_INTERVAL}. Los clientes
     * no tienen expiracion por inactividad y no escriben nada.
     */
    @Override
    @Transactional
    public boolean isActive(UUID sessionId, UUID userId) {
        Instant now = Instant.now();
        Optional<SessionWithOwner> found = sessionRepository.findWithOwner(sessionId)
                .filter(owned -> owned.session().getUserId().equals(userId))
                .filter(owned -> owned.session().getRevokedAt() == null)
                .filter(owned -> owned.session().getExpiresAt().isAfter(now));
        if (found.isEmpty()) {
            return false;
        }
        if (found.get().userType() != UserType.employee) {
            return true;
        }
        if (isIdle(found.get().session().getLastActivityAt(), now)) {
            // Solo el request que la revoca audita: dos requests simultaneos no duplican el evento.
            if (sessionRepository.revokeIfActive(sessionId, now) == 1) {
                auditService.record(found.get().tenantId(), userId, null, AuthAuditService.SESSION_IDLE_EXPIRED,
                        Map.of("sessionId", sessionId.toString(),
                                "idleTimeoutMinutes", sessionProperties.employeeIdleTimeout().toMinutes()));
            }
            return false;
        }
        sessionRepository.touchActivity(sessionId, now, now.minus(ACTIVITY_WRITE_INTERVAL));
        return true;
    }

    /**
     * La actividad guardada puede ir hasta un {@link #ACTIVITY_WRITE_INTERVAL} por detras de la real (se escribe
     * como mucho una vez por intervalo). Esa tolerancia se suma al limite: la sesion nunca se cierra antes de
     * {@code employeeIdleTimeout} de inactividad real, a lo sumo un intervalo despues.
     */
    private boolean isIdle(Instant lastActivityAt, Instant now) {
        Instant idleAt = lastActivityAt.plus(sessionProperties.employeeIdleTimeout()).plus(ACTIVITY_WRITE_INTERVAL);
        return !now.isBefore(idleAt);
    }
}
