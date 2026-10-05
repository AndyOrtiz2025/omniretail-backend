package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.dto.MfaChallengeResponse;
import com.omniretail.backend.auth.dto.ResendMfaCodeRequest;
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
import java.time.Clock;
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
    /** Limite total de un desafio por correo, aunque se reenvie el codigo. */
    static final Duration EMAIL_CHALLENGE_MAX_TTL = Duration.ofMinutes(15);
    static final int MAX_RESENDS = 3;
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
    private final Clock authClock;
    private final MfaCrypto mfaCrypto;
    private final MfaEmailCodeSender emailCodeSender;

    public boolean isEnabled(User user) {
        return enrollmentRepository.existsByUserIdAndEnabledTrue(user.getId());
    }

    /**
     * Un login nuevo invalida los desafios anteriores del usuario que siguieran vivos.
     *
     * <p>Con el metodo email ademas genera un codigo y lo envia por correo (despues del commit). El desafio
     * vive hasta 15 min (renovando el codigo con {@link #resend}) y {@code expiresAt} de la respuesta es la
     * vigencia del codigo, 5 min, igual que con la app. Si la espera o el tope de envios no permiten mandar
     * el correo, la respuesta es exactamente la misma: el usuario pide otro con "reenviar".
     */
    @Transactional
    public MfaChallengeResponse openChallenge(User user, Boolean rememberMe, String deviceLabel, Instant now) {
        challengeRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId())
                .forEach(previous -> previous.setInvalidatedAt(now));
        MfaEnrollment enrollment = enrollmentRepository.findByUserIdForUpdate(user.getId()).orElseThrow();
        boolean byEmail = enrollment.getMethod() == MfaMethod.email;

        String token = AuthTokens.generate();
        Instant codeExpiresAt = now.plus(CHALLENGE_TTL).truncatedTo(ChronoUnit.SECONDS);
        MfaChallenge challenge = challengeRepository.saveAndFlush(MfaChallenge.builder()
                .userId(user.getId())
                .tokenHash(AuthTokens.hash(token))
                .method(enrollment.getMethod())
                .rememberMe(Boolean.TRUE.equals(rememberMe))
                .deviceLabel(deviceLabel)
                .expiresAt(byEmail ? now.plus(EMAIL_CHALLENGE_MAX_TTL).truncatedTo(ChronoUnit.SECONDS) : codeExpiresAt)
                .codeExpiresAt(byEmail ? codeExpiresAt : null)
                .build());
        if (byEmail && emailCodeSender.canSend(enrollment, now)) {
            sendChallengeCode(challenge, enrollment, user, codeExpiresAt, now);
        }
        return new MfaChallengeResponse(true, token, challenge.getMethod().name(), codeExpiresAt);
    }

    /**
     * Reenvia el codigo por correo de un desafio vivo: genera uno nuevo (el anterior deja de servir), con 5
     * min desde ahora sin pasar el limite total del desafio. Conserva los intentos fallidos. Hasta 3 reenvios
     * por desafio, y respeta la espera y el tope por usuario de {@link MfaEmailCodeSender}.
     */
    @Transactional
    public void resend(ResendMfaCodeRequest request) {
        UnknownFields.reject(request.unknownFields());
        Instant now = authClock.instant();
        LiveChallenge live = requireLiveChallenge(request.challengeToken(), now);
        MfaChallenge challenge = live.challenge();
        if (challenge.getMethod() != MfaMethod.email) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MFA_RESEND_NOT_AVAILABLE",
                    "El código se genera en tu app autenticadora.");
        }
        if (challenge.getResendCount() >= MAX_RESENDS || !emailCodeSender.canSend(live.enrollment(), now)) {
            throw resendLimited();
        }

        Instant codeExpiresAt = now.plus(CHALLENGE_TTL).truncatedTo(ChronoUnit.SECONDS);
        if (codeExpiresAt.isAfter(challenge.getExpiresAt())) {
            codeExpiresAt = challenge.getExpiresAt();
        }
        challenge.setResendCount(challenge.getResendCount() + 1);
        sendChallengeCode(challenge, live.enrollment(), live.user(), codeExpiresAt, now);
    }

    /**
     * {@code noRollbackFor}: un codigo incorrecto responde con error, pero el intento sumado al desafio y
     * su auditoria deben quedar guardados. Ninguna otra rama escribe antes de lanzar.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse verify(VerifyMfaChallengeRequest request) {
        UnknownFields.reject(request.unknownFields());
        Instant now = authClock.instant();
        LiveChallenge live = requireLiveChallenge(request.challengeToken(), now);
        MfaChallenge challenge = live.challenge();
        User user = live.user();
        AuthAccount account = live.account();
        MfaEnrollment enrollment = live.enrollment();

        MfaCodeVerifier.EmailCode emailCode = new MfaCodeVerifier.EmailCode(
                challenge.getId(), challenge.getCodeHash(), challenge.getCodeExpiresAt());
        Optional<MfaCodeVerifier.Match> match = codeVerifier.verify(enrollment, request.code(), now, emailCode);
        if (match.isEmpty()) {
            int attempts = challenge.getFailedAttempts() + 1;
            challenge.setFailedAttempts(attempts);
            boolean exhausted = attempts >= MAX_ATTEMPTS;
            if (exhausted) {
                challenge.setInvalidatedAt(now);
            }
            // recordFailure registra mfa_failed (o account_locked si este fallo bloquea la cuenta).
            loginAttemptService.recordFailure(account.getId(), user.getId(), user.getTenantId(),
                    AuthAuditService.MFA_FAILED, Map.of("context", "login", "attempt", attempts));
            throw exhausted ? challengeUnavailable() : codeInvalid();
        }

        challenge.setConsumedAt(now);
        challenge.setCodeHash(null);
        if (match.get() == MfaCodeVerifier.Match.RECOVERY_CODE) {
            auditService.record(user.getTenantId(), user.getId(), account.getId(),
                    AuthAuditService.MFA_RECOVERY_CODE_USED, Map.of("context", "login"));
        }
        // Recien aqui termina el login: mismo reinicio de contadores que AuthService.login sin MFA.
        account.setStatus(AccountStatus.active);
        account.setFailedLoginAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.LOGIN_SUCCESS, null);

        Session session = sessionService.open(user, challenge.getRememberMe(), challenge.getDeviceLabel(), now);
        String jwt = jwtService.generateToken(user, session);
        return new LoginResponse(jwt, session.getExpiresAt(), LoginResponse.UserSummary.from(user));
    }

    private record LiveChallenge(MfaChallenge challenge, User user, AuthAccount account, MfaEnrollment enrollment) {
    }

    /**
     * Desafio vivo con su usuario, cuenta y MFA activo. Desafio vencido, usado o invalidado, usuario o tienda
     * inactivos, o cuenta bloqueada: {@code MFA_CHALLENGE_UNAVAILABLE}, sin consumir nada ni contar un fallo.
     */
    private LiveChallenge requireLiveChallenge(String token, Instant now) {
        if (token == null || token.isBlank()) {
            throw challengeUnavailable();
        }
        MfaChallenge challenge = challengeRepository.findByTokenHashForUpdate(AuthTokens.hash(token.trim()))
                .filter(found -> found.isUsable(now))
                .orElseThrow(MfaLoginService::challengeUnavailable);
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
        return new LiveChallenge(challenge, user, account, enrollment);
    }

    private void sendChallengeCode(MfaChallenge challenge, MfaEnrollment enrollment, User user,
            Instant codeExpiresAt, Instant now) {
        String code = MfaEmailCodeSender.newCode();
        challenge.setCodeHash(mfaCrypto.hashEmailCode(challenge.getId(), code));
        challenge.setCodeExpiresAt(codeExpiresAt);
        challenge.setCodeSentAt(now);
        emailCodeSender.send(enrollment, user, code, MfaEmailCodeSender.Purpose.LOGIN, now);
    }

    static BusinessException resendLimited() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "MFA_CODE_RESEND_LIMITED",
                "Espera un momento antes de pedir otro código.");
    }

    private static BusinessException codeInvalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_CODE_INVALID", CODE_INVALID_MESSAGE);
    }

    private static BusinessException challengeUnavailable() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_CHALLENGE_UNAVAILABLE",
                CHALLENGE_UNAVAILABLE_MESSAGE);
    }
}
