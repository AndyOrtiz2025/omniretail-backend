package com.omniretail.backend;

import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Fixtures comerciales explicitos: Basico sin complementos salvo opt-in del escenario. */
public final class SubscriptionTestFixtures {
    private SubscriptionTestFixtures() {}

    public static TenantSubscription provisionBasic(TenantSubscriptionRepository subscriptions,
            SaasPlanRepository plans, UUID tenantId) {
        return provisionBasic(subscriptions, plans, tenantId, List.of());
    }

    public static TenantSubscription provisionBasic(TenantSubscriptionRepository subscriptions,
            SaasPlanRepository plans, UUID tenantId, List<String> addonCodes) {
        var plan = plans.findByCode("basic").orElseThrow(() -> new IllegalStateException("Falta el seed del plan Basico"));
        Instant start = Instant.now();
        var subscription = TenantSubscription.builder().planId(plan.getId())
                .status(TenantSubscriptionStatus.active).startedAt(start)
                .currentPeriodStart(start).currentPeriodEnd(start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant())
                .addonCodes(addonCodes).build();
        subscription.setTenantId(tenantId);
        return subscriptions.saveAndFlush(subscription);
    }
}
