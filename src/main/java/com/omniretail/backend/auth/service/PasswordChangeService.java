package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.ChangePasswordRequest;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cambio de contrasena de un cliente o empleado autenticado (MockAuthRepository.changePassword, sin MFA).
 * Como el usuario ya inicio sesion con esta cuenta, decirle que su contrasena actual no es correcta no
 * revela nada que no sepa.
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    static final String WRONG_CURRENT_PASSWORD_MESSAGE = "La contraseña actual no es correcta.";
    static final String SAME_PASSWORD_MESSAGE = "La nueva contraseña debe ser diferente a la actual.";

    private final AuthAccountRepository authAccountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;

    /**
     * Valida todo antes de escribir: cualquier error deja la contrasena y las sesiones como estaban. Al
     * cambiarla revoca las demas sesiones del usuario, pero no la actual.
     */
    @Transactional
    public void change(AuthenticatedUser actor, ChangePasswordRequest request) {
        // Bloquea la cuenta: dos cambios simultaneos no pueden validarse contra el mismo hash.
        AuthAccount account = authAccountRepository.findByUserIdForUpdate(actor.userId())
                .orElseThrow(PasswordChangeService::unauthenticated);
        User user = userRepository.findById(actor.userId())
                .filter(found -> found.getTenantId().equals(actor.tenantId()))
                .orElseThrow(PasswordChangeService::unauthenticated);

        String currentPassword = request.currentPassword();
        if (currentPassword == null || currentPassword.isEmpty()
                || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw FieldValidationException.of("currentPassword", WRONG_CURRENT_PASSWORD_MESSAGE);
        }
        if (currentPassword.equals(request.newPassword())) {
            throw FieldValidationException.of("newPassword", SAME_PASSWORD_MESSAGE);
        }
        PasswordPolicy.forUserType(user.getType()).validate(request.newPassword(), user.getEmail())
                .ifPresent(message -> {
                    throw FieldValidationException.of("newPassword", message);
                });

        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        account.setPasswordChangedAt(Instant.now());
        sessionService.revokeOtherSessions(user.getId(), actor.sessionId());
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Debes iniciar sesion.");
    }
}
