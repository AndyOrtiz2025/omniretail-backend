package com.omniretail.backend.auth.service;

import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.AuthAuditLog;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.AuthAuditLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bloqueo escalonado por intentos fallidos, igual que registerFailedAuthAttempt de MockAuthRepository
 * y auth-policy.ts. Se calcula a partir de los eventos de {@code auth_audit_logs}, no de un contador:
 *
 * <ul>
 *   <li>Cuentan como fallo {@code login_failed}, {@code mfa_failed} y {@code account_locked}, posteriores
 *       al ultimo {@code login_success} y de los ultimos 10 minutos (ventana movil). Contrasena y codigo
 *       MFA comparten el mismo contador.</li>
 *   <li>El 5.o fallo bloquea la cuenta 15, 30 o 60 minutos segun los bloqueos de las ultimas 24 h.</li>
 *   <li>La escala vuelve a 15 si pasaron mas de 60 minutos sin fallos ni bloqueos antes de la racha.</li>
 *   <li>Un login correcto corta la racha, pero no baja el nivel de la escala.</li>
 * </ul>
 *
 * <p>Los intentos con la cuenta ya bloqueada no se registran ni cuentan. Corre en su propia transaccion
 * ({@code REQUIRES_NEW}): el login lanza el error de credenciales justo despues y eso deshace la suya,
 * pero no esta. Bloquea la fila de la cuenta (FOR UPDATE): dos intentos simultaneos se cuentan de a uno.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** attemptNumber con triggersLockout de LOGIN_ATTEMPT_RULES. */
    static final int LOCKOUT_ATTEMPT = 5;
    /** FAILED_ATTEMPTS_WINDOW_MINUTES. */
    static final Duration FAILED_ATTEMPTS_WINDOW = Duration.ofMinutes(10);
    /** LOCKOUT_ESCALATION_LOOKBACK_HOURS. */
    static final Duration LOCKOUT_ESCALATION_LOOKBACK = Duration.ofHours(24);
    /** LOCKOUT_RESET_AFTER_MINUTES. */
    static final Duration LOCKOUT_RESET_AFTER = Duration.ofMinutes(60);

    private static final Set<String> COUNTABLE_FAILURES = Set.of(
            AuthAuditService.LOGIN_FAILED, AuthAuditService.MFA_FAILED, AuthAuditService.ACCOUNT_LOCKED);
    private static final Set<String> RELEVANT_EVENTS = Set.of(
            AuthAuditService.LOGIN_FAILED, AuthAuditService.MFA_FAILED, AuthAuditService.ACCOUNT_LOCKED,
            AuthAuditService.LOGIN_SUCCESS);

    private final AuthAccountRepository authAccountRepository;
    private final AuthAuditLogRepository auditLogRepository;
    private final AuthAuditService auditService;
    private final Clock authClock;

    /** LOCKOUT_ESCALATION_MINUTES / getLockoutMinutesForOccurrence: 15, 30 y desde el tercero 60. */
    static Duration lockDurationFor(int occurrenceIn24h) {
        if (occurrenceIn24h <= 1) {
            return Duration.ofMinutes(15);
        }
        return occurrenceIn24h == 2 ? Duration.ofMinutes(30) : Duration.ofMinutes(60);
    }

    /**
     * Registra un fallo de autenticacion de la cuenta.
     *
     * @param failureAction {@code login_failed} (contrasena) o {@code mfa_failed} (codigo MFA). Si este fallo
     *     bloquea la cuenta se registra {@code account_locked} en su lugar, igual que el mock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID accountId, UUID userId, UUID tenantId, String failureAction,
            Map<String, Object> metadata) {
        Instant now = authClock.instant();
        AuthAccount account = authAccountRepository.findForUpdate(accountId).orElseThrow();
        if (account.getStatus() == AccountStatus.temporarily_locked) {
            if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
                return; // Ya bloqueada (quiza por un intento simultaneo): no se cuenta.
            }
            account.setStatus(AccountStatus.active); // El bloqueo vencio.
            account.setLockedUntil(null);
        }

        // Todo lo que importa cae en las ultimas 24 h: lo anterior no cambia ni la racha ni el nivel.
        List<AuthAuditLog> events = auditLogRepository.findRecentEvents(
                accountId, now.minus(LOCKOUT_ESCALATION_LOOKBACK), RELEVANT_EVENTS);
        Instant lastSuccessAt = events.stream()
                .filter(event -> AuthAuditService.LOGIN_SUCCESS.equals(event.getAction()))
                .map(AuthAuditLog::getCreatedAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.MIN);
        List<AuthAuditLog> countable = events.stream()
                .filter(event -> COUNTABLE_FAILURES.contains(event.getAction()))
                .toList();
        List<Instant> streak = countable.stream()
                .map(AuthAuditLog::getCreatedAt)
                .filter(at -> at.isAfter(lastSuccessAt))
                .filter(at -> Duration.between(at, now).compareTo(FAILED_ATTEMPTS_WINDOW) < 0)
                .toList();

        int attemptNumber = streak.size() + 1;
        account.setFailedLoginAttempts(attemptNumber); // Solo informativo, como en el mock.
        if (attemptNumber < LOCKOUT_ATTEMPT) {
            auditService.record(tenantId, userId, accountId, failureAction, metadata);
            return;
        }

        Instant streakStart = streak.stream().min(Comparator.naturalOrder()).orElse(now);
        Instant lastBeforeStreak = countable.stream()
                .map(AuthAuditLog::getCreatedAt)
                .filter(at -> at.isBefore(streakStart))
                .max(Comparator.naturalOrder())
                .orElse(null);
        boolean escalationReset = lastBeforeStreak != null
                && Duration.between(lastBeforeStreak, streakStart).compareTo(LOCKOUT_RESET_AFTER) > 0;
        long recentLockouts = escalationReset ? 0 : countable.stream()
                .filter(event -> AuthAuditService.ACCOUNT_LOCKED.equals(event.getAction()))
                .count();

        account.setStatus(AccountStatus.temporarily_locked);
        account.setLockedUntil(now.plus(lockDurationFor((int) recentLockouts + 1)));
        auditService.record(tenantId, userId, accountId, AuthAuditService.ACCOUNT_LOCKED, metadata);
    }
}
