package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.ResetPasswordResponse;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.PasswordResetChallenge;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.PasswordResetChallengeRepository;
import com.omniretail.backend.shared.config.FrontendProperties;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recuperacion de contrasena (mismas reglas que MockAuthRepository.requestPasswordReset/resetPassword).
 * Recuperacion y verificacion/activacion son maquinas de estado separadas: solo cuentas active o
 * temporarily_locked pueden recuperar la contrasena.
 */
@Service
@RequiredArgsConstructor
public class PasswordRecoveryService {

    static final Duration PASSWORD_RESET_TTL = Duration.ofMinutes(15);
    static final int PASSWORD_RESET_REQUEST_LIMIT = 3;
    static final Duration PASSWORD_RESET_COOLDOWN = Duration.ofMinutes(30);
    static final Set<AccountStatus> RECOVERY_ELIGIBLE_STATUSES =
            Set.of(AccountStatus.active, AccountStatus.temporarily_locked);
    static final String INVALID_TOKEN_MESSAGE = "Este enlace no es válido o ya expiró.";

    private final AuthAccountRepository authAccountRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordResetChallengeRepository challengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final FrontendProperties frontendProperties;

    private record Candidate(AuthAccount account, User user) {
    }

    /**
     * Crea un challenge por cada cuenta candidata: clientes de la tienda del {@code tenantSlug} activo
     * y empleados de cualquier tenant (mismo criterio que el login). Las cuentas no elegibles o que ya
     * llegaron al limite de solicitudes se omiten en silencio. No devuelve nada: el llamador responde
     * siempre lo mismo.
     */
    @Transactional
    public void requestReset(String email, String tenantSlug) {
        Instant now = Instant.now();
        Optional<Tenant> customerTenant = resolveCustomerTenant(tenantSlug);
        for (AuthAccount found : authAccountRepository.findByEmail(email)) {
            toCandidate(found, customerTenant)
                    .filter(candidate -> RECOVERY_ELIGIBLE_STATUSES.contains(candidate.account().getStatus()))
                    .ifPresent(candidate -> createChallenge(candidate, customerTenant, now));
        }
    }

    /**
     * Cambia la contrasena con un challenge vigente, revoca todas las sesiones (R-A24) y desbloquea
     * una cuenta temporarily_locked. Nunca reactiva una cuenta disabled o archived (ni siquiera cambia
     * su contrasena). Cualquier problema con el token produce el mismo error generico.
     */
    @Transactional
    public ResetPasswordResponse resetPassword(String token, String newPassword) {
        Instant now = Instant.now();
        PasswordResetChallenge challenge = challengeRepository.findByTokenHashForUpdate(AuthTokens.hash(token))
                .filter(found -> found.getUsedAt() == null && found.getSupersededAt() == null)
                .filter(found -> now.isBefore(found.getExpiresAt()))
                .orElseThrow(PasswordRecoveryService::invalidToken);
        AuthAccount account = authAccountRepository.findByUserIdForUpdate(challenge.getUserId())
                .filter(found -> RECOVERY_ELIGIBLE_STATUSES.contains(found.getStatus()))
                .orElseThrow(PasswordRecoveryService::invalidToken);
        User user = userRepository.findById(account.getUserId()).orElseThrow(PasswordRecoveryService::invalidToken);

        PasswordPolicy.forUserType(user.getType()).validate(newPassword, user.getEmail())
                .ifPresent(message -> {
                    throw FieldValidationException.of("newPassword", message);
                });

        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setPasswordChangedAt(now);
        if (account.getStatus() == AccountStatus.temporarily_locked) {
            account.setStatus(AccountStatus.active);
            account.setFailedLoginAttempts(0);
            account.setLockedUntil(null);
        }
        sessionService.revokeAllSessions(user.getId());
        challenge.setUsedAt(now);

        String tenantSlug = user.getType() == UserType.customer
                ? tenantRepository.findById(user.getTenantId()).map(Tenant::getSlug).orElse(null)
                : null;
        return new ResetPasswordResponse(user.getType(), tenantSlug);
    }

    /** Solo un tenantSlug de una tienda activa habilita cuentas de cliente; sin el, no hay ninguna. */
    private Optional<Tenant> resolveCustomerTenant(String tenantSlug) {
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return Optional.empty();
        }
        return tenantRepository.findBySlug(tenantSlug.trim()).filter(tenant -> tenant.getStatus() == TenantStatus.active);
    }

    private Optional<Candidate> toCandidate(AuthAccount account, Optional<Tenant> customerTenant) {
        return userRepository.findById(account.getUserId())
                .filter(user -> user.getType() == UserType.employee
                        || customerTenant.map(tenant -> tenant.getId().equals(user.getTenantId())).orElse(false))
                .map(user -> new Candidate(account, user));
    }

    private void createChallenge(Candidate candidate, Optional<Tenant> customerTenant, Instant now) {
        // Bloquea la cuenta: dos solicitudes simultaneas no pueden saltarse el limite.
        authAccountRepository.findForUpdate(candidate.account().getId());
        User user = candidate.user();
        if (challengeRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(PASSWORD_RESET_COOLDOWN))
                >= PASSWORD_RESET_REQUEST_LIMIT) {
            return; // R-A21: limite alcanzado, se omite en silencio.
        }
        // Una nueva solicitud invalida el enlace anterior.
        challengeRepository.findByUserIdAndUsedAtIsNullAndSupersededAtIsNull(user.getId())
                .forEach(previous -> previous.setSupersededAt(now));

        String token = AuthTokens.generate();
        challengeRepository.save(PasswordResetChallenge.builder()
                .userId(user.getId())
                .tokenHash(AuthTokens.hash(token))
                .createdAt(now)
                .expiresAt(now.plus(PASSWORD_RESET_TTL))
                .build());

        String path = user.getType() == UserType.customer
                ? "/tienda/" + customerTenant.orElseThrow().getSlug() + "/restablecer-contrasena/" + token
                : "/restablecer-contrasena/" + token;
        eventPublisher.publishEvent(new EmailRequestedEvent(
                resetEmail(user.getTenantId(), candidate.account().getEmail(), user.getName(),
                        frontendProperties.link(path))));
    }

    private static EmailMessage resetEmail(UUID tenantId, String to, String name, String link) {
        String body = """
                Hola %s:

                Recibimos una solicitud para restablecer tu contraseña. Para elegir una nueva, usa este enlace:

                %s

                El enlace vence en %d minutos y solo se puede usar una vez. Si no pediste este cambio, ignora
                este mensaje: tu contraseña actual sigue siendo válida.
                """.formatted(name, link, PASSWORD_RESET_TTL.toMinutes());
        return EmailMessage.text(tenantId, EmailPurpose.PASSWORD_RESET, to, "Restablece tu contraseña", body);
    }

    private static BusinessException invalidToken() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MESSAGE);
    }
}
