package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantSubscription;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanLimitGuardTest {
    @Mock private UserRepository users;
    @Mock private BranchRepository branches;
    @Mock private TenantRepository tenants;
    @Mock private TenantSubscriptionRepository subscriptions;
    @Mock private SaasPlanRepository plans;
    @InjectMocks private PlanLimitGuard guard;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @Test
    void unlimitedUsageHasNullRemainingAndNeverReached() {
        when(users.countByTenantIdAndTypeAndStatusNot(tenantId, UserType.employee, UserStatus.archived)).thenReturn(500L);
        var usage = guard.evaluateUsage(tenantId, limits(null, null));
        assertThat(usage.maxEmployees().current()).isEqualTo(500L);
        assertThat(usage.maxEmployees().limit()).isNull();
        assertThat(usage.maxEmployees().remaining()).isNull();
        assertThat(usage.maxEmployees().reached()).isFalse();
        assertThat(usage.maxBranches().exceeded()).isFalse();
        verify(branches).countByTenantIdAndStatusNot(tenantId, BranchStatus.archived);
    }

    @Test
    void reachedAndExceededUsageIsInformationalAndDoesNotRejectDowngrade() {
        when(users.countByTenantIdAndTypeAndStatusNot(tenantId, UserType.employee, UserStatus.archived)).thenReturn(3L);
        when(branches.countByTenantIdAndStatusNot(tenantId, BranchStatus.archived)).thenReturn(4L);
        var usage = guard.evaluateUsage(tenantId, limits(3, 2));
        assertThat(usage.maxEmployees().key()).isEqualTo("maxEmployees");
        assertThat(usage.maxEmployees().reached()).isTrue();
        assertThat(usage.maxEmployees().exceeded()).isFalse();
        assertThat(usage.maxBranches().key()).isEqualTo("maxBranches");
        assertThat(usage.maxBranches().exceeded()).isTrue();
        assertThat(usage.maxBranches().remaining()).isZero();
    }

    @Test
    void employeeCreationLocksTenantBeforeCountingAndRejectsAtBoundary() {
        setup(limits(3, null));
        when(users.countByTenantIdAndTypeAndStatusNot(tenantId, UserType.employee, UserStatus.archived)).thenReturn(3L);
        assertLimit(() -> guard.ensureEmployeeCreationAllowed(tenantId));
        var order = inOrder(tenants, subscriptions, plans, users);
        order.verify(tenants).findByIdForUpdate(tenantId);
        order.verify(subscriptions).findByTenantIdAndStatusIn(tenantId,
                List.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended));
        order.verify(plans).findById(planId);
        order.verify(users).countByTenantIdAndTypeAndStatusNot(tenantId, UserType.employee, UserStatus.archived);
    }

    @Test
    void branchCreationRejectsAtBoundaryIncludingInactiveResources() {
        setup(limits(null, 2));
        when(branches.countByTenantIdAndStatusNot(tenantId, BranchStatus.archived)).thenReturn(2L);
        assertLimit(() -> guard.ensureBranchCreationAllowed(tenantId));
        verify(tenants).findByIdForUpdate(tenantId);
    }

    @Test
    void allowsNextCreationWhenBelowLimit() {
        setup(limits(3, 2));
        when(users.countByTenantIdAndTypeAndStatusNot(tenantId, UserType.employee, UserStatus.archived)).thenReturn(2L);
        guard.ensureEmployeeCreationAllowed(tenantId);
        guard.ensureBranchCreationAllowed(tenantId);
    }

    @Test
    void missingSubscriptionFailsClosedBeforeCounting() {
        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(new Tenant()));
        assertThatThrownBy(() -> guard.ensureEmployeeCreationAllowed(tenantId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("SUBSCRIPTION_INACTIVE"));
        verifyNoInteractions(users, branches);
    }

    private void setup(SaasPlan plan) {
        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(new Tenant()));
        when(subscriptions.findByTenantIdAndStatusIn(tenantId,
                List.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended)))
                .thenReturn(Optional.of(TenantSubscription.builder().planId(planId).status(TenantSubscriptionStatus.active).build()));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
    }

    private static SaasPlan limits(Integer employees, Integer branches) {
        return SaasPlan.builder().status(PlanStatus.active).maxEmployees(employees).maxBranches(branches).build();
    }

    private static void assertLimit(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("PLAN_LIMIT_EXCEEDED"));
    }
}
