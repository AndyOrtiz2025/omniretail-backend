package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.dto.SaasPlanResponse;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.administration.service.DefaultTenantEntitlementResolver;
import com.omniretail.backend.auth.dto.SessionEntitlementsResponse;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.SaasCapability;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entitlements del negocio de la sesion para cualquier empleado activo, sin {@code admin.plans.read}.
 *
 * <p>El frontend decide con esto si muestra acciones de un modulo (POS, inventario, recepcion...). Es la
 * misma regla con la que el backend valida cada operacion ({@link DefaultTenantEntitlementResolver}), asi
 * la interfaz y el servidor no pueden divergir. Solo lectura: no renueva, no factura y no toma locks.
 */
@Service
@RequiredArgsConstructor
public class SessionEntitlementsService {

    static final String NOT_ALLOWED_MESSAGE = "La sesión no puede consultar los entitlements del negocio.";

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SaasPlanRepository planRepository;

    @Transactional(readOnly = true)
    public SessionEntitlementsResponse getSessionEntitlements(AuthenticatedUser actor) {
        if (actor.userType() != UserType.employee) {
            throw notAllowed();
        }
        requireLiveSession(actor);
        requireActiveEmployee(actor);

        var subscription = DefaultTenantEntitlementResolver
                .findEffectiveSubscription(subscriptionRepository, actor.tenantId())
                .orElseThrow(() -> notFound("TENANT_SUBSCRIPTION_NOT_FOUND",
                        "El negocio no tiene una suscripción."));
        var plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> notFound("SAAS_PLAN_NOT_FOUND", "El plan de la suscripción no existe."));

        boolean subscriptionActive = subscription.getStatus() == TenantSubscriptionStatus.active;
        boolean planActive = plan.getStatus() == PlanStatus.active;
        boolean entitlementActive = subscriptionActive && planActive;
        List<String> capabilities = DefaultTenantEntitlementResolver.capabilitiesOf(plan, subscription).stream()
                .map(SaasCapability::getKey)
                .toList();

        return new SessionEntitlementsResponse(
                actor.tenantId(),
                plan.getCode(),
                plan.getStatus(),
                subscription.getStatus(),
                entitlementActive,
                capabilities,
                entitlementActive ? capabilities : List.of(),
                SaasPlanResponse.limitsOf(plan));
    }

    /** Sesion del token: del mismo usuario, sin revocar y vigente. */
    private void requireLiveSession(AuthenticatedUser actor) {
        Instant now = Instant.now();
        sessionRepository.findById(actor.sessionId())
                .filter(found -> found.getUserId().equals(actor.userId()))
                .filter(found -> found.getRevokedAt() == null && found.getExpiresAt().isAfter(now))
                .orElseThrow(SessionEntitlementsService::notAllowed);
    }

    /** Empleado activo del tenant del token. */
    private void requireActiveEmployee(AuthenticatedUser actor) {
        userRepository.findByTenantIdAndId(actor.tenantId(), actor.userId())
                .filter(found -> found.getType() == UserType.employee && found.getStatus() == UserStatus.active)
                .orElseThrow(SessionEntitlementsService::notAllowed);
    }

    private static BusinessException notAllowed() {
        return new BusinessException(HttpStatus.FORBIDDEN, "ENTITLEMENTS_NOT_ALLOWED", NOT_ALLOWED_MESSAGE);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
