package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import java.time.Instant;
import java.util.UUID;

public record TenantSubscriptionResponse(
        UUID id,
        UUID tenantId,
        TenantSubscriptionStatus status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        SaasPlanResponse plan,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantSubscriptionResponse from(TenantSubscription subscription, SaasPlan plan) {
        return new TenantSubscriptionResponse(
                subscription.getId(),
                subscription.getTenantId(),
                subscription.getStatus(),
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.isCancelAtPeriodEnd(),
                SaasPlanResponse.from(plan),
                subscription.getCreatedAt(),
                subscription.getUpdatedAt());
    }
}
