package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Los limites solo bloquean la siguiente alta; nunca invalidan el uso ya existente. */
@Component
@RequiredArgsConstructor
public class PlanLimitGuard {
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SaasPlanRepository planRepository;

    /** El lock se conserva hasta que la transaccion de createUser complete el alta. */
    @Transactional
    public void ensureEmployeeCreationAllowed(UUID tenantId) {
        SaasPlan plan = lockAndResolvePlan(tenantId);
        ensureCreationAllowed(LimitUsage.of("maxEmployees",
                userRepository.countByTenantIdAndTypeAndStatusNot(tenantId, UserType.employee, UserStatus.archived),
                plan.getMaxEmployees()));
    }

    /** El mismo lock serializa altas de sucursales, empleados y cambios comerciales. */
    @Transactional
    public void ensureBranchCreationAllowed(UUID tenantId) {
        SaasPlan plan = lockAndResolvePlan(tenantId);
        ensureCreationAllowed(LimitUsage.of("maxBranches",
                branchRepository.countByTenantIdAndStatusNot(tenantId, BranchStatus.archived), plan.getMaxBranches()));
    }

    @Transactional(readOnly = true)
    public PlanUsage evaluateUsage(UUID tenantId, SaasPlan plan) {
        return new PlanUsage(
                LimitUsage.of("maxEmployees", userRepository.countByTenantIdAndTypeAndStatusNot(
                        tenantId, UserType.employee, UserStatus.archived), plan.getMaxEmployees()),
                LimitUsage.of("maxBranches", branchRepository.countByTenantIdAndStatusNot(
                        tenantId, BranchStatus.archived), plan.getMaxBranches()));
    }

    private SaasPlan lockAndResolvePlan(UUID tenantId) {
        tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() ->
                BusinessException.conflict("TENANT_NOT_FOUND", "El negocio no existe."));
        var subscription = subscriptionRepository.findByTenantIdAndStatusIn(
                tenantId, List.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended))
                .filter(current -> current.getStatus() == TenantSubscriptionStatus.active)
                .orElseThrow(() -> BusinessException.conflict(
                        "SUBSCRIPTION_INACTIVE", "El negocio no tiene una suscripcion activa."));
        return planRepository.findById(subscription.getPlanId())
                .filter(plan -> plan.getStatus() == PlanStatus.active)
                .orElseThrow(() -> BusinessException.conflict("PLAN_INACTIVE", "El plan no esta activo."));
    }

    private static void ensureCreationAllowed(LimitUsage usage) {
        if (usage.reached()) {
            throw BusinessException.conflict("PLAN_LIMIT_EXCEEDED",
                    "No se puede crear otro recurso de " + usage.key() + ": uso " + usage.current()
                            + ", limite " + usage.limit() + ".");
        }
    }

    public record PlanUsage(LimitUsage maxEmployees, LimitUsage maxBranches) {}

    public record LimitUsage(String key, long current, Integer limit, Long remaining, boolean reached, boolean exceeded) {
        private static LimitUsage of(String key, long current, Integer limit) {
            return new LimitUsage(key, current, limit, limit == null ? null : Math.max((long) limit - current, 0L),
                    limit != null && current >= limit, limit != null && current > limit);
        }
    }
}
