package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record SaasPlanResponse(
        UUID id, String code, String name, String description, BigDecimal monthlyQuetzales,
        PlanStatus status, List<String> capabilities, Map<String, Integer> limits,
        Instant createdAt, Instant updatedAt) {
    public static SaasPlanResponse from(SaasPlan plan) {
        return new SaasPlanResponse(plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(),
                plan.getMonthlyQuetzales(), plan.getStatus(), List.copyOf(plan.getCapabilities()),
                limitsOf(plan), plan.getCreatedAt(), plan.getUpdatedAt());
    }

    /** Limites numericos del plan; omite los que no tienen tope. */
    public static Map<String, Integer> limitsOf(SaasPlan plan) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        if (plan.getMaxEmployees() != null) {
            limits.put("maxEmployees", plan.getMaxEmployees());
        }
        if (plan.getMaxBranches() != null) {
            limits.put("maxBranches", plan.getMaxBranches());
        }
        return Map.copyOf(limits);
    }
}
