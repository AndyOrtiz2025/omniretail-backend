package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SaasPlanCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaasPlanResponse(
        UUID id,
        String code,
        String name,
        String description,
        int maxBranches,
        int maxUsers,
        int maxProducts,
        BigDecimal priceMonthly,
        SaasPlanCurrency currency,
        List<String> capabilities,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static SaasPlanResponse from(SaasPlan plan) {
        return new SaasPlanResponse(
                plan.getId(),
                plan.getCode(),
                plan.getName(),
                plan.getDescription(),
                plan.getMaxBranches(),
                plan.getMaxUsers(),
                plan.getMaxProducts(),
                plan.getPriceMonthly(),
                plan.getCurrency(),
                List.copyOf(plan.getCapabilities()),
                plan.isActive(),
                plan.getCreatedAt(),
                plan.getUpdatedAt());
    }
}
