package com.omniretail.backend.auth.service;

import com.omniretail.backend.auth.entity.AuthAuditLog;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auditoria de autenticacion (logAuthAudit de MockAuthRepository): eventos de login, bloqueo y MFA. El
 * bloqueo por intentos se calcula a partir de estos eventos (ver LoginAttemptService). Nunca guarda
 * contrasenas, codigos, secretos ni tokens en {@code metadata}.
 */
@Service
@RequiredArgsConstructor
public class AuthAuditService {

    /** Solo cuando de verdad se entrega una sesion (login sin MFA o desafio MFA completado). */
    public static final String LOGIN_SUCCESS = "login_success";
    public static final String LOGIN_FAILED = "login_failed";
    public static final String ACCOUNT_LOCKED = "account_locked";
    public static final String MFA_ENABLED = "mfa_enabled";
    public static final String MFA_DISABLED = "mfa_disabled";
    public static final String MFA_FAILED = "mfa_failed";
    public static final String MFA_RECOVERY_CODE_USED = "mfa_recovery_code_used";
    public static final String EXTERNAL_IDENTITY_LINKED = "external_identity_linked";

    private final AuthAuditLogRepository auditLogRepository;
    private final Clock authClock;

    /** Se guarda dentro de la transaccion actual: si esta se deshace, el evento tampoco queda. */
    @Transactional
    public void record(UUID tenantId, UUID userId, UUID accountId, String action, Map<String, Object> metadata) {
        auditLogRepository.save(AuthAuditLog.builder()
                .tenantId(tenantId)
                .actorUserId(userId)
                .authAccountId(accountId)
                .action(action)
                .metadata(metadata)
                .createdAt(authClock.instant())
                .build());
    }

    /**
     * En su propia transaccion ({@code REQUIRES_NEW}): para fallos que terminan en una excepcion que
     * deshace la transaccion del llamador, y que igual deben quedar registrados.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(
            UUID tenantId, UUID userId, UUID accountId, String action, Map<String, Object> metadata) {
        record(tenantId, userId, accountId, action, metadata);
    }
}
