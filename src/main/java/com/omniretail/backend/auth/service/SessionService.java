package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.shared.security.SessionRevoker;
import com.omniretail.backend.shared.security.SessionValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    private final SessionRepository sessionRepository;

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

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID sessionId, UUID userId) {
        Instant now = Instant.now();
        return sessionRepository.findById(sessionId)
                .filter(session -> session.getUserId().equals(userId))
                .filter(session -> session.getRevokedAt() == null)
                .filter(session -> session.getExpiresAt().isAfter(now))
                .isPresent();
    }
}
