package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.dto.GoogleLoginRequest;
import com.omniretail.backend.auth.dto.LoginOutcome;
import com.omniretail.backend.auth.dto.LoginResponse;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.AuthExternalIdentity;
import com.omniretail.backend.auth.entity.ExternalIdentityProvider;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.AuthExternalIdentityRepository;
import com.omniretail.backend.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.omniretail.backend.shared.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inicio de sesion de clientes con Google (ID token de Google Identity Services).
 *
 * <ul>
 *   <li>Cuenta ya vinculada a ese {@code sub} en la tienda: entra.</li>
 *   <li>Correo de un cliente de la tienda sin vincular: se vincula (Google confirmo el correo). Si la cuenta
 *       seguia pendiente de verificacion se activa, pero su contrasena se reemplaza por una aleatoria y se
 *       revocan sus sesiones: quien la registro pudo no ser el dueno del correo (pre-secuestro).</li>
 *   <li>Correo nuevo en la tienda: se crea el cliente, activo, sin contrasena utilizable (para tener una,
 *       "Olvide mi contrasena").</li>
 * </ul>
 *
 * <p>Responde igual que {@code POST /auth/login}: la sesion, o el desafio de MFA si el cliente lo tiene
 * activo. Todo rechazo usa el mismo error generico del login, y un token invalido no suma intentos
 * fallidos (no se sabe a que cuenta apuntaba). Respeta el bloqueo y el estado de la cuenta.
 */
@Service
@RequiredArgsConstructor
public class GoogleLoginService {

    static final String NOT_CONFIGURED_MESSAGE = "Inicio de sesión con Google no configurado.";
    private static final int NAME_MAX_LENGTH = 200;

    private final GoogleIdTokenVerifier tokenVerifier;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AuthAccountRepository authAccountRepository;
    private final AuthExternalIdentityRepository identityRepository;
    private final CustomerAccountFactory customerAccountFactory;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final JwtService jwtService;
    private final MfaLoginService mfaLoginService;
    private final AuthAuditService auditService;
    private final Clock authClock;

    private record Target(User user, AuthAccount account) {
    }

    @Transactional
    public LoginOutcome login(GoogleLoginRequest request) {
        if (!tokenVerifier.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_LOGIN_NOT_CONFIGURED",
                    NOT_CONFIGURED_MESSAGE);
        }
        if (request.expectedUserType() != null && request.expectedUserType() != UserType.customer) {
            throw invalidCredentials();
        }
        GoogleIdentity identity = tokenVerifier.verify(request.idToken()).orElseThrow(GoogleLoginService::invalidCredentials);
        Tenant tenant = activeTenant(request.tenantSlug()).orElseThrow(GoogleLoginService::invalidCredentials);
        Instant now = authClock.instant();

        Optional<AuthExternalIdentity> linked = identityRepository.findByProviderAndSubjectAndTenantId(
                ExternalIdentityProvider.google, identity.subject(), tenant.getId());
        Target target = linked.isPresent()
                ? linkedTarget(linked.get(), tenant, now)
                : linkByEmail(identity, tenant, now);

        User user = target.user();
        if (mfaLoginService.isEnabled(user)) {
            return mfaLoginService.openChallenge(user, request.rememberMe(), request.deviceLabel(), now);
        }
        AuthAccount account = target.account();
        account.setStatus(AccountStatus.active);
        account.setFailedLoginAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.LOGIN_SUCCESS,
                Map.of("method", "google"));

        Session session = sessionService.open(user, request.rememberMe(), request.deviceLabel(), now);
        String token = jwtService.generateToken(user, session);
        return new LoginResponse(token, session.getExpiresAt(), LoginResponse.UserSummary.from(user));
    }

    /** Ya vinculada: solo se revisa que la cuenta pueda entrar. */
    private Target linkedTarget(AuthExternalIdentity linked, Tenant tenant, Instant now) {
        AuthAccount account = authAccountRepository.findForUpdate(linked.getAccountId())
                .orElseThrow(GoogleLoginService::invalidCredentials);
        User user = userRepository.findById(account.getUserId()).orElseThrow(GoogleLoginService::invalidCredentials);
        requireEligible(user, tenant, account, now, false);
        return new Target(user, account);
    }

    private Target linkByEmail(GoogleIdentity identity, Tenant tenant, Instant now) {
        String email = identity.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> existing = userRepository.findByTenantIdAndEmail(tenant.getId(), email);
        if (existing.isEmpty()) {
            // Mismo correo con otras mayusculas: no se adivina a que cuenta corresponde.
            if (userRepository.existsByTenantIdAndEmailIgnoreCase(tenant.getId(), email)) {
                throw invalidCredentials();
            }
            return createCustomer(identity, tenant, email, now);
        }

        User user = existing.get();
        AuthAccount account = authAccountRepository.findByUserIdForUpdate(user.getId())
                .orElseThrow(GoogleLoginService::invalidCredentials);
        boolean pending = account.getStatus() == AccountStatus.pending_verification;
        requireEligible(user, tenant, account, now, pending);
        // Ya vinculada a otra cuenta de Google: no se re-vincula en silencio.
        if (identityRepository.findByAccountIdAndProvider(account.getId(), ExternalIdentityProvider.google).isPresent()) {
            throw invalidCredentials();
        }
        if (pending) {
            account.setPasswordHash(passwordEncoder.encode(AuthTokens.generate()));
            account.setStatus(AccountStatus.active);
            sessionService.revokeAllSessions(user.getId());
        }
        link(identity, user, account, email, pending ? "pending_verification" : "existing_account", now);
        return new Target(user, account);
    }

    private Target createCustomer(GoogleIdentity identity, Tenant tenant, String email, Instant now) {
        CustomerAccountFactory.CreatedCustomer created;
        try {
            // Sin contrasena utilizable, igual que una invitacion de empleado.
            created = customerAccountFactory.create(tenant.getId(), nameOf(identity, email), email, null,
                    passwordEncoder.encode(AuthTokens.generate()), AccountStatus.active);
        } catch (CustomerAccountFactory.EmailTakenException ex) {
            throw invalidCredentials(); // Alta concurrente con el mismo correo.
        }
        link(identity, created.user(), created.account(), email, "new_account", now);
        return new Target(created.user(), created.account());
    }

    private void link(GoogleIdentity identity, User user, AuthAccount account, String email, String reason, Instant now) {
        try {
            identityRepository.saveAndFlush(AuthExternalIdentity.builder()
                    .accountId(account.getId())
                    .tenantId(user.getTenantId())
                    .provider(ExternalIdentityProvider.google)
                    .subject(identity.subject())
                    .email(email)
                    .linkedAt(now)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw invalidCredentials(); // Vinculacion concurrente.
        }
        auditService.record(user.getTenantId(), user.getId(), account.getId(), AuthAuditService.EXTERNAL_IDENTITY_LINKED,
                Map.of("provider", ExternalIdentityProvider.google.name(), "reason", reason));
    }

    /** Mismas condiciones que el login con contrasena: cliente activo de esta tienda y cuenta sin bloqueo vigente. */
    private static void requireEligible(User user, Tenant tenant, AuthAccount account, Instant now, boolean allowPending) {
        boolean eligible = user.getType() == UserType.customer
                && user.getStatus() == UserStatus.active
                && tenant.getId().equals(user.getTenantId())
                && (AuthService.canAuthenticate(account, now)
                        || (allowPending && account.getStatus() == AccountStatus.pending_verification));
        if (!eligible) {
            throw invalidCredentials();
        }
    }

    private Optional<Tenant> activeTenant(String tenantSlug) {
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return Optional.empty();
        }
        return tenantRepository.findBySlug(tenantSlug.trim()).filter(tenant -> tenant.getStatus() == TenantStatus.active);
    }

    /** Nombre de la cuenta de Google; si no viene, la parte local del correo. */
    private static String nameOf(GoogleIdentity identity, String email) {
        int at = email.indexOf('@');
        String name = identity.name() == null || identity.name().isBlank()
                ? (at > 0 ? email.substring(0, at) : email)
                : identity.name().trim();
        return name.length() > NAME_MAX_LENGTH ? name.substring(0, NAME_MAX_LENGTH) : name;
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", AuthService.INVALID_CREDENTIALS_MESSAGE);
    }
}
