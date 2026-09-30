package com.omniretail.backend.administration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SaasPlanCurrency;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TenantSubscriptionRepositoryIntegrationTest {

    private static final EnumSet<TenantSubscriptionStatus> CURRENT =
            EnumSet.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.trialing);

    @Autowired private TenantRepository tenants;
    @Autowired private SaasPlanRepository plans;
    @Autowired private TenantSubscriptionRepository subscriptions;

    @Test
    void currentQueriesAreTenantQualifiedAndLockedVariantReturnsSameRow() {
        SaasPlan plan = persistPlan();
        Tenant firstTenant = persistTenant();
        Tenant secondTenant = persistTenant();
        TenantSubscription first = persist(firstTenant.getId(), plan.getId(), TenantSubscriptionStatus.active);
        persist(secondTenant.getId(), plan.getId(), TenantSubscriptionStatus.trialing);

        assertThat(subscriptions.findByTenantIdAndStatusIn(firstTenant.getId(), CURRENT))
                .get().extracting(TenantSubscription::getId).isEqualTo(first.getId());
        assertThat(subscriptions.findCurrentByTenantIdForUpdate(firstTenant.getId(), CURRENT))
                .get().extracting(TenantSubscription::getId).isEqualTo(first.getId());
        assertThat(subscriptions.findByTenantIdAndStatusIn(UUID.randomUUID(), CURRENT)).isEmpty();
    }

    @Test
    void partialUniqueIndexAllowsHistoryButRejectsTwoCurrentSubscriptions() {
        SaasPlan plan = persistPlan();
        Tenant tenant = persistTenant();
        persist(tenant.getId(), plan.getId(), TenantSubscriptionStatus.canceled);
        persist(tenant.getId(), plan.getId(), TenantSubscriptionStatus.past_due);
        persist(tenant.getId(), plan.getId(), TenantSubscriptionStatus.active);

        assertThatThrownBy(() -> persist(
                        tenant.getId(), plan.getId(), TenantSubscriptionStatus.trialing))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TenantSubscription persist(UUID tenantId, UUID planId, TenantSubscriptionStatus status) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TenantSubscription subscription = TenantSubscription.builder()
                .planId(planId)
                .status(status)
                .currentPeriodStart(start)
                .currentPeriodEnd(start.plus(30, ChronoUnit.DAYS))
                .cancelAtPeriodEnd(false)
                .build();
        subscription.setTenantId(tenantId);
        return subscriptions.saveAndFlush(subscription);
    }

    private SaasPlan persistPlan() {
        String suffix = UUID.randomUUID().toString();
        return plans.saveAndFlush(SaasPlan.builder()
                .code("repo-" + suffix)
                .name("Repository plan " + suffix)
                .maxBranches(1)
                .maxUsers(1)
                .maxProducts(1)
                .priceMonthly(BigDecimal.ONE)
                .currency(SaasPlanCurrency.USD)
                .capabilities(List.of("pos"))
                .active(true)
                .build());
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        return tenants.saveAndFlush(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("USD")
                .timezone("UTC")
                .build());
    }
}
