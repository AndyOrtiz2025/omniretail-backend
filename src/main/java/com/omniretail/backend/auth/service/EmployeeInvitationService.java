package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmployeeInvitation;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.EmployeeInvitationRepository;
import com.omniretail.backend.shared.config.FrontendProperties;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import com.omniretail.backend.shared.security.EmployeeAuthSummary;
import com.omniretail.backend.shared.security.EmployeeInvitationPort;
import com.omniretail.backend.shared.security.EmployeeInviteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aprovisiona cuentas pendientes e invitaciones sin acoplar administration a auth. */
@Service
@RequiredArgsConstructor
public class EmployeeInvitationService implements EmployeeInvitationPort {

    static final Duration INVITATION_TTL = Duration.ofHours(48);

    private final UserRepository userRepository;
    private final EmployeeInvitationRepository invitationRepository;
    private final AuthAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final FrontendProperties frontendProperties;

    @Override
    @Transactional
    public EmployeeInviteResult inviteEmployee(UUID tenantId, UUID userId) {
        Instant now = Instant.now();
        // El usuario serializa tambien el alta de una cuenta que aun no existe.
        // Orden global de locks: usuario -> invitaciones -> cuenta.
        User user = userRepository.findByTenantIdAndIdForUpdate(tenantId, userId)
                .filter(found -> found.getType() == UserType.employee)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND", "Usuario no encontrado."));
        invitationRepository.findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(userId)
                .forEach(previous -> previous.setSupersededAt(now));
        AuthAccount account = accountRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> createPendingAccount(user));

        if (account.getStatus() == AccountStatus.active) {
            throw BusinessException.conflict("ACCOUNT_ALREADY_ACTIVE", "Este empleado ya tiene una cuenta activa.");
        }
        if (account.getStatus() != AccountStatus.password_reset_required) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVITATION_NOT_ALLOWED",
                    "No se puede invitar a este empleado en su estado actual.");
        }
        // User.email es la fuente administrativa de verdad; JPA persiste la sincronizacion.
        account.setEmail(user.getEmail());

        String token = AuthTokens.generate();
        Instant expiresAt = now.plus(INVITATION_TTL);
        invitationRepository.save(EmployeeInvitation.builder()
                .userId(userId)
                .tenantId(tenantId)
                .tokenHash(AuthTokens.hash(token))
                .createdAt(now)
                .expiresAt(expiresAt)
                .build());
        eventPublisher.publishEvent(new EmailRequestedEvent(invitationEmail(user,
                frontendProperties.link("/activar-cuenta/" + token))));
        return new EmployeeInviteResult(userId, token, expiresAt);
    }

    @Override
    public List<EmployeeAuthSummary> getAuthSummaries(UUID tenantId, Collection<UUID> userIds) {
        // Task 5 implementara el batch; no devolver resultados ficticios mientras tanto.
        throw new UnsupportedOperationException("Consulta de autenticación pendiente de implementar en Task 5.");
    }

    private AuthAccount createPendingAccount(User user) {
        AuthAccount account = AuthAccount.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .passwordHash(passwordEncoder.encode(AuthTokens.generate()))
                .status(AccountStatus.password_reset_required)
                .failedLoginAttempts(0)
                .build();
        accountRepository.save(account);
        return account;
    }

    private static EmailMessage invitationEmail(User user, String link) {
        String body = """
                Hola %s:

                Te invitamos a activar tu cuenta de empleado. Para elegir tu contraseña, usa este enlace:

                %s

                El enlace vence en %d horas y solo se puede usar una vez.
                Si recibes una nueva invitación, el enlace anterior dejará de ser válido.
                """.formatted(user.getName(), link, INVITATION_TTL.toHours());
        return new EmailMessage(user.getEmail(), "Activa tu cuenta de empleado", body);
    }
}
