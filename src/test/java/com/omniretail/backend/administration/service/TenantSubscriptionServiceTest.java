package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.dto.AssignTenantSubscriptionRequest;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SaasPlanCurrency;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantSubscriptionServiceTest {

    @Mock private TenantSubscriptionRepository subscriptions;
    @Mock private SaasPlanRepository plans;
    @Mock private TenantRepository tenants;
    @Mock private PlanLimitGuard limits;
    @Mock private CurrentUser currentUser;

    private TenantSubscriptionService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TenantSubscriptionService(subscriptions, plans, tenants, limits, currentUser);
        when(currentUser.require()).thenReturn(new AuthenticatedUser(
                UUID.randomUUID(), tenantId, UserType.employee, UUID.randomUUID(), null, UUID.randomUUID()));
    }

    @Test
    void currentSubscriptionIsAlwaysResolvedInsideAuthenticatedTenant() {
        TenantSubscription subscription = subscription(tenantId, planId, TenantSubscriptionStatus.active);
        SaasPlan plan = plan(planId, true);
        when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.of(subscription));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));

        var response = service.getCurrent();

        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.plan().id()).isEqualTo(planId);
        verify(subscriptions).findByTenantIdAndStatusIn(eq(tenantId), currentStatuses());
    }

    @Test
    void currentSubscriptionCannotLeakFromAnotherTenant() {
        UUID otherTenantId = UUID.randomUUID();
        when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.empty());

        assertCode(service::getCurrent, "TENANT_SUBSCRIPTION_NOT_FOUND");
        verify(subscriptions, never()).findByTenantIdAndStatusIn(eq(otherTenantId), any());
        verify(plans, never()).findById(any());
    }

    @Test
    void assignLocksTenantAndPlanChecksLimitsAndPersistsTenantOwnership() {
        SaasPlan plan = plan(planId, true);
        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
        when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.empty());
        when(plans.findByIdForUpdate(planId)).thenReturn(Optional.of(plan));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        var response = service.assign(new AssignTenantSubscriptionRequest(planId));
        Instant after = Instant.now();

        verify(tenants).findByIdForUpdate(tenantId);
        verify(plans).findByIdForUpdate(planId);
        verify(limits).ensureUsageFits(tenantId, plan);
        ArgumentCaptor<TenantSubscription> captor = ArgumentCaptor.forClass(TenantSubscription.class);
        verify(subscriptions).save(captor.capture());
        TenantSubscription saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getStatus()).isEqualTo(TenantSubscriptionStatus.active);
        assertThat(saved.getCurrentPeriodStart()).isBetween(before, after);
        assertThat(saved.getCurrentPeriodEnd())
                .isEqualTo(ZonedDateTime.ofInstant(saved.getCurrentPeriodStart(), ZoneOffset.UTC)
                        .plusMonths(1).toInstant());
        assertThat(response.cancelAtPeriodEnd()).isFalse();
    }

    @Test
    void assignRejectsExistingCurrentSubscriptionBeforeLockingPlanOrCheckingLimits() {
        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
        when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.of(subscription(tenantId, planId, TenantSubscriptionStatus.trialing)));

        assertCode(() -> service.assign(new AssignTenantSubscriptionRequest(planId)), "TENANT_SUBSCRIPTION_EXISTS");
        verify(plans, never()).findByIdForUpdate(any());
        verify(limits, never()).ensureUsageFits(any(), any());
        verify(subscriptions, never()).save(any());
    }

    @Test
    void assignRejectsInactivePlanBeforeUsageValidation() {
        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
        when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.empty());
        when(plans.findByIdForUpdate(planId)).thenReturn(Optional.of(plan(planId, false)));

        assertCode(() -> service.assign(new AssignTenantSubscriptionRequest(planId)), "SAAS_PLAN_INACTIVE");
        verify(limits, never()).ensureUsageFits(any(), any());
        verify(subscriptions, never()).save(any());
    }

    @Test
    void assignPropagatesPlanLimitFailureWithoutSaving() {
        SaasPlan plan = plan(planId, true);
        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(Tenant.builder().build()));
        when(subscriptions.findByTenantIdAndStatusIn(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.empty());
        when(plans.findByIdForUpdate(planId)).thenReturn(Optional.of(plan));
        org.mockito.Mockito.doThrow(BusinessException.conflict("PLAN_LIMIT_EXCEEDED", "limit"))
                .when(limits).ensureUsageFits(tenantId, plan);

        assertCode(() -> service.assign(new AssignTenantSubscriptionRequest(planId)), "PLAN_LIMIT_EXCEEDED");
        verify(subscriptions, never()).save(any());
    }

    @Test
    void renewUsesLockedSubscriptionAndStartsAfterFutureCurrentPeriod() {
        Instant oldEnd = Instant.parse("2030-01-31T10:15:30Z");
        TenantSubscription subscription = subscription(tenantId, planId, TenantSubscriptionStatus.trialing);
        subscription.setCurrentPeriodEnd(oldEnd);
        subscription.setCancelAtPeriodEnd(true);
        SaasPlan plan = plan(planId, true);
        when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.of(subscription));
        when(plans.findByIdForUpdate(planId)).thenReturn(Optional.of(plan));
        when(subscriptions.save(subscription)).thenReturn(subscription);

        var response = service.renew();

        verify(subscriptions).findCurrentByTenantIdForUpdate(eq(tenantId), currentStatuses());
        verify(limits).ensureUsageFits(tenantId, plan);
        assertThat(response.status()).isEqualTo(TenantSubscriptionStatus.active);
        assertThat(response.currentPeriodStart()).isEqualTo(oldEnd);
        assertThat(response.currentPeriodEnd()).isEqualTo(Instant.parse("2030-02-28T10:15:30Z"));
        assertThat(response.cancelAtPeriodEnd()).isFalse();
    }

    @Test
    void cancelAtPeriodEndUsesLockedTenantQualifiedLookup() {
        TenantSubscription subscription = subscription(tenantId, planId, TenantSubscriptionStatus.active);
        SaasPlan plan = plan(planId, true);
        when(subscriptions.findCurrentByTenantIdForUpdate(eq(tenantId), currentStatuses()))
                .thenReturn(Optional.of(subscription));
        when(subscriptions.save(subscription)).thenReturn(subscription);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));

        var response = service.cancelAtPeriodEnd();

        assertThat(response.cancelAtPeriodEnd()).isTrue();
        verify(subscriptions).findCurrentByTenantIdForUpdate(eq(tenantId), currentStatuses());
        verify(plans, never()).findByIdForUpdate(any());
    }

    private static Collection<TenantSubscriptionStatus> currentStatuses() {
        return org.mockito.ArgumentMatchers.argThat(statuses ->
                statuses.size() == 2
                        && statuses.contains(TenantSubscriptionStatus.active)
                        && statuses.contains(TenantSubscriptionStatus.trialing));
    }

    private static SaasPlan plan(UUID id, boolean active) {
        SaasPlan plan = SaasPlan.builder()
                .code("starter")
                .name("Starter")
                .maxBranches(1)
                .maxUsers(3)
                .maxProducts(100)
                .priceMonthly(BigDecimal.TEN)
                .currency(SaasPlanCurrency.USD)
                .capabilities(List.of("pos"))
                .active(active)
                .build();
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private static TenantSubscription subscription(
            UUID tenantId, UUID planId, TenantSubscriptionStatus status) {
        TenantSubscription subscription = TenantSubscription.builder()
                .planId(planId)
                .status(status)
                .currentPeriodStart(Instant.parse("2029-12-31T10:15:30Z"))
                .currentPeriodEnd(Instant.parse("2030-01-31T10:15:30Z"))
                .build();
        subscription.setTenantId(tenantId);
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        return subscription;
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
