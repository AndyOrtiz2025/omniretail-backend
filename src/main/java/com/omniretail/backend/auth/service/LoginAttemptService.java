package com.omniretail.backend.auth.service;

import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra intentos fallidos en su propia transaccion ({@code REQUIRES_NEW}): el login lanza la
 * excepcion de credenciales invalidas justo despues, y eso deshace la transaccion del login, pero
 * no esta.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AuthAccountRepository authAccountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID accountId, Instant now) {
        AuthAccount account = authAccountRepository.findForUpdate(accountId).orElseThrow();
        if (account.getStatus() == AccountStatus.temporarily_locked) {
            if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
                return; // Otro request concurrente ya la bloqueo.
            }
            // El bloqueo anterior ya vencio: el conteo empieza de nuevo.
            account.setStatus(AccountStatus.active);
            account.setFailedLoginAttempts(0);
            account.setLockedUntil(null);
        }
        int attempts = account.getFailedLoginAttempts() + 1;
        account.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            account.setStatus(AccountStatus.temporarily_locked);
            account.setLockedUntil(now.plus(LOCK_DURATION));
        }
    }
}
