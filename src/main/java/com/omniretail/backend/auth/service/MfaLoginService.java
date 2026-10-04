package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.dto.MfaChallengeResponse;
import com.omniretail.backend.auth.dto.VerifyMfaChallengeRequest;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.MfaChallenge;
import com.omniretail.backend.auth.entity.MfaEnrollment;
import com.omniretail.backend.auth.entity.MfaMethod;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.MfaChallengeRepository;
import com.omniretail.backend.auth.repository.MfaEnrollmentRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.validation.UnknownFields;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Segundo paso del login (login con {@code mfa_required} y verifyMfaChallenge de MockAuthRepository,
 * regla R-A16): la contrasena correcta no crea sesion; abre un desafio de un solo uso que vence en 5
 * minutos y admite 5 intentos. La sesion se crea solo con un codigo correcto.
 *
 * <p>Cada codigo incorrecto suma tambien al contador de bloqueo de la cuenta (el mismo de la contrasena),
 * asi reiniciar el login no regala intentos. Con la cuenta bloqueada, ni un codigo correcto entra.
 */
@Service
@RequiredArgsConstructor
public class MfaLoginService {

    /** MFA_CHALLENGE_MAX_ATTEMPTS de mfa-policy.ts. */
    static final int MAX_ATTEMPTS = 5;
    /** MFA_CHALLENGE_EXPIRATION_MINUTES de mfa-policy.ts. */
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final String CODE_INVALID_MESSAGE = "El código no es correcto. Inténtalo de nuevo.";
    static final String CHALLENGE_UNAVAILABLE_MESSAGE = "El código no es válido o venció. Vuelve a iniciar sesión.";

    private final MfaEnrollmentRepository enrollmentRepository;
    private final MfaChallengeRepository challengeRepository;
    private final AuthAccountRepository authAccountRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final MfaCodeVerifier codeVerifier;
    private final LoginAttemptService loginAttemptService;
    private final SessionService sessionService;
    private final JwtService jwtService;
    private final AuthAuditService auditService;

    public boolean isEnabled(User user) {
        return enrollmentRepository.existsByUserIdAndEnabledTrue(user.getId());
    }

    /** Un login nuevo invalida los desafios anteriores del usuario que siguieran vivos. */
    @Transactional
    public MfaChallengeResponse openChallenge(User user, Boolean rememberMe, String deviceLabel, Instant now) {
        challengeRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId())
                .forEach(previous -> previous.setInvalidatedAt(now));

        String token = AuthTokens.generate();
        MfaChallenge challenge = challengeRepository.save(MfaChallenge.builder()
                .userId(user.getId())
                .tokenHash(AuthTokens.hash(token))
                .method(MfaMethod.totp)
                .rememberMe(Boolean.TRUE.equals(rememberMe))
                .deviceLabel(deviceLabel)
                .expiresAt(now.plus(CHALLENGE_TTL).truncatedTo(ChronoUnit.SECONDS))
                .build());
        return new MfaChallengeResponse(true, token, challenge.getMethod().name(), challenge.getExpiresAt());
    }

    /**
     * {@code noRollbackFor}: un codigo incorrecto responde con error, pero el intento sumado al desafio y
     * su auditoria deben quedar guardados. Ninguna otra rama escribe antes de lanzar.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse verify(VerifyMfaChallengeRequest request) {
        UnknownFields.reject(request.unknownFields());
        Instant now = Instant.now();
        String token = request.challengeToken();
        if (token == null || token.isBlank()) {
            throw challengeUnavailable();
        }

        MfaChallenge challenge = challengeRepository.findByTokenHashForUpdate(AuthTokens.hash(token.trim()))
                .filter(found -> found.isUsable(now))
                .orElseThrow(MfaLoginService::challengeUnavailable);
        // Usuario o tienda inactivos, o cuenta bloqueada: no se consume nada ni se cuenta como fallo.
        User user = userRepository.findById(challenge.getUserId())
                .filter(found -> found.getStatus() == UserStatus.active)
                .filter(found -> tenantRepository.findById(found.getTenantId())
                        .map(tenant -> tenant.getStatus() == TenantStatus.active)
                        .orElse(false))
                .orElseThrow(MfaLoginService::challengeUnavailable);
        AuthAccount account = authAccountRepository.findByUserId(user.getId())
                .filter(found -> AuthService.canAuthenticate(found, now))
                .orElseThrow(MfaLoginService::challengeUnavailable);
        MfaEnrollment enrollment = enrollmentRepository.findByUserIdForUpdate(user.getId())
                .filter(found -> Boolean.TRUE.equals(found.getEnabled()))
                .orElseThrow(MfaLoginService::challengeUnavailable);

        Optional<MfaCodeVerifier.Match> match = codeVerifier.verify(enrollment, request.code(), now);
        if (match.isEmpty()) {
            int attempts = challenge.getFailedAttempts() + 1;
            challenge.setFailedAttempts(attempts);
            boolean exhausted = attempts >= MAX_ATTEMPTS;
            if (exhausted) {
                challenge.setInvalidatedAt(now);
            }
            auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.MFA_FAILED,
                    Map.of("context", "login", "attempt", attempts));
            loginAttemptService.recordFailure(account.getId(), now);
            throw exhausted ? challengeUnavailable() : codeInvalid();
        }

        challenge.setConsumedAt(now);
        if (match.get() == MfaCodeVerifier.Match.RECOVERY_CODE) {
            auditService.record(user.getTenantId(), user.getId(), account.getId(),
                    AuthAuditService.MFA_RECOVERY_CODE_USED, Map.of("context", "login"));
        }
        // Recien aqui termina el login: mismo reinicio de contadores que AuthService.login sin MFA.
        account.setStatus(AccountStatus.active);
        account.setFailedLoginAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);

        Session session = sessionService.open(user, challenge.getRememberMe(), challenge.getDeviceLabel(), now);
        String jwt = jwtService.generateToken(user, session);
        return new LoginResponse(jwt, session.getExpiresAt(), LoginResponse.UserSummary.from(user));
    }

    private static BusinessException codeInvalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_CODE_INVALID", CODE_INVALID_MESSAGE);
    }

    private static BusinessException challengeUnavailable() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_CHALLENGE_UNAVAILABLE",
                CHALLENGE_UNAVAILABLE_MESSAGE);
    }
}
