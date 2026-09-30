package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.AssignTenantSubscriptionRequest;
import com.omniretail.backend.administration.dto.TenantSubscriptionResponse;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class TenantSubscriptionService {

    private static final Set<TenantSubscriptionStatus> CURRENT_STATUSES =
            EnumSet.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.trialing);

    private final TenantSubscriptionRepository subscriptionRepository;
    private final SaasPlanRepository planRepository;
    private final TenantRepository tenantRepository;
    private final PlanLimitGuard planLimitGuard;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public TenantSubscriptionResponse getCurrent() {
        UUID tenantId = currentUser.require().tenantId();
        TenantSubscription subscription = subscriptionRepository
                .findByTenantIdAndStatusIn(tenantId, CURRENT_STATUSES)
                .orElseThrow(TenantSubscriptionService::subscriptionNotFound);
        return response(subscription);
    }

    public TenantSubscriptionResponse assign(AssignTenantSubscriptionRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant no encontrado."));
        if (subscriptionRepository.findByTenantIdAndStatusIn(tenantId, CURRENT_STATUSES).isPresent()) {
            throw BusinessException.conflict(
                    "TENANT_SUBSCRIPTION_EXISTS", "El tenant ya tiene una suscripcion vigente.");
        }
        SaasPlan plan = requireActivePlanForUpdate(request.planId());
        planLimitGuard.ensureUsageFits(tenantId, plan);

        Instant periodStart = Instant.now();
        TenantSubscription subscription = TenantSubscription.builder()
                .planId(plan.getId())
                .status(TenantSubscriptionStatus.active)
                .currentPeriodStart(periodStart)
                .currentPeriodEnd(plusOneMonth(periodStart))
                .cancelAtPeriodEnd(false)
                .build();
        subscription.setTenantId(tenantId);
        return TenantSubscriptionResponse.from(subscriptionRepository.save(subscription), plan);
    }

    public TenantSubscriptionResponse renew() {
        UUID tenantId = currentUser.require().tenantId();
        TenantSubscription subscription = requireCurrentForUpdate(tenantId);
        SaasPlan plan = requireActivePlanForUpdate(subscription.getPlanId());
        planLimitGuard.ensureUsageFits(tenantId, plan);

        Instant now = Instant.now();
        Instant periodStart = subscription.getCurrentPeriodEnd().isAfter(now)
                ? subscription.getCurrentPeriodEnd()
                : now;
        subscription.setCurrentPeriodStart(periodStart);
        subscription.setCurrentPeriodEnd(plusOneMonth(periodStart));
        subscription.setStatus(TenantSubscriptionStatus.active);
        subscription.setCancelAtPeriodEnd(false);
        return TenantSubscriptionResponse.from(subscriptionRepository.save(subscription), plan);
    }

    public TenantSubscriptionResponse cancelAtPeriodEnd() {
        UUID tenantId = currentUser.require().tenantId();
        TenantSubscription subscription = requireCurrentForUpdate(tenantId);
        subscription.setCancelAtPeriodEnd(true);
        return response(subscriptionRepository.save(subscription));
    }

    private TenantSubscription requireCurrentForUpdate(UUID tenantId) {
        return subscriptionRepository.findCurrentByTenantIdForUpdate(tenantId, CURRENT_STATUSES)
                .orElseThrow(TenantSubscriptionService::subscriptionNotFound);
    }

    private SaasPlan requireActivePlanForUpdate(UUID planId) {
        SaasPlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SAAS_PLAN_NOT_FOUND", "Plan SaaS no encontrado."));
        if (!plan.isActive()) {
            throw BusinessException.conflict("SAAS_PLAN_INACTIVE", "El plan SaaS no esta activo.");
        }
        return plan;
    }

    private TenantSubscriptionResponse response(TenantSubscription subscription) {
        SaasPlan plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SAAS_PLAN_NOT_FOUND", "Plan SaaS no encontrado."));
        return TenantSubscriptionResponse.from(subscription, plan);
    }

    private static Instant plusOneMonth(Instant value) {
        return ZonedDateTime.ofInstant(value, ZoneOffset.UTC).plusMonths(1).toInstant();
    }

    private static BusinessException subscriptionNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "TENANT_SUBSCRIPTION_NOT_FOUND", "Suscripcion vigente no encontrada.");
    }
}
