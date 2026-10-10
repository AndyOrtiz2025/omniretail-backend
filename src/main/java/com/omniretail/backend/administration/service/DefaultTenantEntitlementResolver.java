package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resuelve capacidades del catalogo y complementos, sin renovar ni interpretar pagos. */
@Service
@RequiredArgsConstructor
public class DefaultTenantEntitlementResolver implements TenantEntitlementResolver {
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SaasPlanRepository planRepository;

    @Override
    @Transactional(readOnly = true)
    public TenantEntitlements resolve(UUID tenantId) {
        var subscription = subscriptionRepository.findByTenantIdAndStatusIn(
                tenantId, List.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended)).orElse(null);
        if (subscription == null) {
            return new TenantEntitlements(false, false, EnumSet.noneOf(SaasCapability.class));
        }
        var plan = planRepository.findById(subscription.getPlanId()).orElse(null);
        boolean subscriptionActive = subscription.getStatus() == TenantSubscriptionStatus.active;
        boolean planActive = plan != null && plan.getStatus() == PlanStatus.active;
        EnumSet<SaasCapability> capabilities = EnumSet.noneOf(SaasCapability.class);
        if (subscriptionActive && planActive) {
            capabilities.addAll(capabilitiesOf(plan, subscription));
        }
        return new TenantEntitlements(subscriptionActive, planActive, capabilities);
    }

    /**
     * Capacidades conocidas del plan mas las de los complementos de la suscripcion, en el orden del enum.
     * No mira el estado: es la unica regla de composicion y la usa tambien el endpoint de sesion.
     */
    public static List<SaasCapability> capabilitiesOf(SaasPlan plan, TenantSubscription subscription) {
        var keys = new HashSet<String>();
        if (plan.getCapabilities() != null) {
            keys.addAll(plan.getCapabilities());
        }
        keys.addAll(SubscriptionAddonCatalog.capabilities(subscription.getAddonCodes()));
        return java.util.Arrays.stream(SaasCapability.values())
                .filter(capability -> keys.contains(capability.getKey()))
                .toList();
    }
}
