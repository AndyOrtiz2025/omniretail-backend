package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.LoginRequest;
import com.omniretail.backend.auth.dto.LoginOutcome;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    static final String INVALID_CREDENTIALS_MESSAGE =
            "No fue posible iniciar sesión. Verifica tus credenciales o intenta más tarde.";

    private final AuthAccountRepository authAccountRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final SessionService sessionService;
    private final JwtService jwtService;
    private final MfaLoginService mfaLoginService;
    private final AuthAuditService auditService;
    private final Clock authClock;
    /** Hash ficticio: sin candidatos igual se compara una contrasena, para no revelar si el email existe. */
    private final String dummyPasswordHash;

    public AuthService(AuthAccountRepository authAccountRepository, UserRepository userRepository,
            TenantRepository tenantRepository, PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService, SessionService sessionService, JwtService jwtService,
            MfaLoginService mfaLoginService, AuthAuditService auditService, Clock authClock) {
        this.authAccountRepository = authAccountRepository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.sessionService = sessionService;
        this.jwtService = jwtService;
        this.mfaLoginService = mfaLoginService;
        this.auditService = auditService;
        this.authClock = authClock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    private record Candidate(AuthAccount account, User user) {
    }

    /**
     * Sin MFA devuelve la sesion, exactamente como antes. Con MFA activo la contrasena correcta no crea
     * sesion (R-A16): abre un desafio y los contadores de bloqueo no se reinician hasta el codigo correcto.
     * {@code login_success} solo se registra cuando se entrega la sesion: si la contrasena correcta cortara la
     * racha, alternar "codigos malos y login de nuevo" nunca bloquearia la cuenta.
     */
    @Transactional
    public LoginOutcome login(LoginRequest request) {
        Instant now = authClock.instant();
        UUID customerTenantId = resolveCustomerTenant(request.tenantSlug());
        // Solo quedan cuentas que pueden entrar: si todas se descartan, la lista queda vacia.
        List<Candidate> candidates = authAccountRepository.findByEmail(request.email()).stream()
                .map(account -> toCandidate(account, customerTenantId, now, request.expectedUserType()))
                .flatMap(Optional::stream)
                .toList();
        if (candidates.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw invalidCredentials();
        }

        List<Candidate> matches = candidates.stream()
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.account().getPasswordHash()))
                .toList();
        if (matches.isEmpty()) {
            // Con varias candidatas no se sabe a cual apuntaba el intento: no se muta ninguna.
            if (candidates.size() == 1) {
                Candidate target = candidates.get(0);
                loginAttemptService.recordFailure(target.account().getId(), target.user().getId(),
                        target.user().getTenantId(), AuthAuditService.LOGIN_FAILED, null);
            }
            throw invalidCredentials();
        }
        if (matches.size() > 1) {
            throw invalidCredentials(); // Ambiguo: la misma contrasena abre varias cuentas.
        }

        Candidate match = matches.get(0);
        // Defensa en profundidad: toCandidate ya filtra por tipo; nunca se muta una cuenta de otro tipo.
        if (request.expectedUserType() != null && match.user().getType() != request.expectedUserType()) {
            throw invalidCredentials();
        }
        if (mfaLoginService.isEnabled(match.user())) {
            return mfaLoginService.openChallenge(match.user(), request.rememberMe(), request.deviceLabel(), now);
        }
        AuthAccount account = match.account();
        account.setStatus(AccountStatus.active);
        account.setFailedLoginAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        auditService.record(match.user().getTenantId(), match.user().getId(), account.getId(),
                AuthAuditService.LOGIN_SUCCESS, null);

        Session session = sessionService.open(match.user(), request.rememberMe(), request.deviceLabel(), now);
        String token = jwtService.generateToken(match.user(), session);
        return new LoginResponse(token, session.getExpiresAt(), LoginResponse.UserSummary.from(match.user()));
    }

    public void logout(UUID sessionId) {
        sessionService.revoke(sessionId);
    }

    /** Solo un tenantSlug de una tienda activa habilita cuentas de cliente; sin el, no hay ninguna. */
    private UUID resolveCustomerTenant(String tenantSlug) {
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return null;
        }
        return tenantRepository.findBySlug(tenantSlug.trim())
                .filter(tenant -> tenant.getStatus() == TenantStatus.active)
                .map(tenant -> tenant.getId())
                .orElse(null);
    }

    private Optional<Candidate> toCandidate(AuthAccount account, UUID customerTenantId, Instant now,
            UserType expectedUserType) {
        if (!canAuthenticate(account, now)) {
            return Optional.empty();
        }
        return userRepository.findById(account.getUserId())
                .filter(user -> user.getStatus() == UserStatus.active)
                .filter(user -> expectedUserType == null || user.getType() == expectedUserType)
                .filter(user -> user.getType() == UserType.employee || user.getTenantId().equals(customerTenantId))
                .filter(user -> tenantRepository.findById(user.getTenantId())
                        .map(tenant -> tenant.getStatus() == TenantStatus.active)
                        .orElse(false))
                .map(user -> new Candidate(account, user));
    }

    /** Activa, o bloqueada temporalmente con el bloqueo ya vencido. */
    static boolean canAuthenticate(AuthAccount account, Instant now) {
        return account.getStatus() == AccountStatus.active
                || (account.getStatus() == AccountStatus.temporarily_locked
                        && account.getLockedUntil() != null
                        && !account.getLockedUntil().isAfter(now));
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS_MESSAGE);
    }
}
