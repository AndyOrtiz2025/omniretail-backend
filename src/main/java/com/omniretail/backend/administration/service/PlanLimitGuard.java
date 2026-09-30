package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Valida que el uso real del tenant quepa dentro de los limites del plan. */
@Component
@RequiredArgsConstructor
public class PlanLimitGuard {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;

    public void ensureUsageFits(UUID tenantId, SaasPlan plan) {
        PlanUsage usage = evaluateUsage(tenantId, plan);
        ensureWithin(usage.maxUsers());
        ensureWithin(usage.maxBranches());
        ensureWithin(usage.maxProducts());
    }

    /** Devuelve el estado actual de cada limite para mostrarlo o tomar decisiones sin provocar una excepcion. */
    public PlanUsage evaluateUsage(UUID tenantId, SaasPlan plan) {
        long users = userRepository.countByTenantIdAndTypeAndStatus(
                tenantId, UserType.employee, UserStatus.active);
        long branches = branchRepository.countByTenantIdAndStatus(tenantId, BranchStatus.active);
        long products = productRepository.countByTenantIdAndStatus(tenantId, ProductStatus.published);

        return new PlanUsage(
                LimitUsage.of("max_users", users, plan.getMaxUsers()),
                LimitUsage.of("max_branches", branches, plan.getMaxBranches()),
                LimitUsage.of("max_products", products, plan.getMaxProducts()));
    }

    private static void ensureWithin(LimitUsage usage) {
        if (usage.exceeded()) {
            throw BusinessException.conflict(
                    "PLAN_LIMIT_EXCEEDED",
                    "El uso actual de " + usage.key() + " (" + usage.current()
                            + ") excede el limite del plan (" + usage.limit() + ").");
        }
    }

    public record PlanUsage(LimitUsage maxUsers, LimitUsage maxBranches, LimitUsage maxProducts) {
    }

    public record LimitUsage(String key, long current, int limit, long remaining, boolean reached, boolean exceeded) {

        private static LimitUsage of(String key, long current, int limit) {
            return new LimitUsage(
                    key,
                    current,
                    limit,
                    Math.max((long) limit - current, 0L),
                    current >= limit,
                    current > limit);
        }
    }
}
