package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanLimitGuardTest {

    @Mock private UserRepository users;
    @Mock private BranchRepository branches;
    @Mock private ProductRepository products;
    private PlanLimitGuard guard;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new PlanLimitGuard(users, branches, products);
    }

    @Test
    void acceptsUsageExactlyAtEveryLimitAndCountsOnlyActiveResources() {
        when(users.countByTenantIdAndTypeAndStatus(tenantId, UserType.employee, UserStatus.active)).thenReturn(3L);
        when(branches.countByTenantIdAndStatus(tenantId, BranchStatus.active)).thenReturn(2L);
        when(products.countByTenantIdAndStatus(tenantId, ProductStatus.published)).thenReturn(100L);

        guard.ensureUsageFits(tenantId, limits(2, 3, 100));

        verify(users).countByTenantIdAndTypeAndStatus(tenantId, UserType.employee, UserStatus.active);
        verify(branches).countByTenantIdAndStatus(tenantId, BranchStatus.active);
        verify(products).countByTenantIdAndStatus(tenantId, ProductStatus.published);
    }

    @Test
    void reportsReachedAndRemainingForEveryLimit() {
        when(users.countByTenantIdAndTypeAndStatus(tenantId, UserType.employee, UserStatus.active)).thenReturn(3L);
        when(branches.countByTenantIdAndStatus(tenantId, BranchStatus.active)).thenReturn(1L);
        when(products.countByTenantIdAndStatus(tenantId, ProductStatus.published)).thenReturn(101L);

        PlanLimitGuard.PlanUsage usage = guard.evaluateUsage(tenantId, limits(2, 3, 100));

        assertThat(usage.maxUsers().reached()).isTrue();
        assertThat(usage.maxUsers().exceeded()).isFalse();
        assertThat(usage.maxUsers().remaining()).isZero();
        assertThat(usage.maxBranches().reached()).isFalse();
        assertThat(usage.maxBranches().remaining()).isEqualTo(1L);
        assertThat(usage.maxProducts().reached()).isTrue();
        assertThat(usage.maxProducts().exceeded()).isTrue();
        assertThat(usage.maxProducts().remaining()).isZero();
    }

    @Test
    void rejectsEachLimitExceededByOne() {
        when(users.countByTenantIdAndTypeAndStatus(tenantId, UserType.employee, UserStatus.active)).thenReturn(4L);
        assertLimit(() -> guard.ensureUsageFits(tenantId, limits(2, 3, 100)), "max_users", 4, 3);

        when(users.countByTenantIdAndTypeAndStatus(tenantId, UserType.employee, UserStatus.active)).thenReturn(3L);
        when(branches.countByTenantIdAndStatus(tenantId, BranchStatus.active)).thenReturn(3L);
        assertLimit(() -> guard.ensureUsageFits(tenantId, limits(2, 3, 100)), "max_branches", 3, 2);

        when(branches.countByTenantIdAndStatus(tenantId, BranchStatus.active)).thenReturn(2L);
        when(products.countByTenantIdAndStatus(tenantId, ProductStatus.published)).thenReturn(101L);
        assertLimit(() -> guard.ensureUsageFits(tenantId, limits(2, 3, 100)), "max_products", 101, 100);
    }

    private static SaasPlan limits(int branches, int users, int products) {
        return SaasPlan.builder().maxBranches(branches).maxUsers(users).maxProducts(products).build();
    }

    private static void assertLimit(Runnable action, String resource, long current, int limit) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PLAN_LIMIT_EXCEEDED");
                    assertThat(exception.getMessage())
                            .contains(resource, Long.toString(current), Integer.toString(limit));
                });
    }
}
