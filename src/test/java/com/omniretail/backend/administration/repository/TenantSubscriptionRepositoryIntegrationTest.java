package com.omniretail.backend.administration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SubscriptionInvoice;
import com.omniretail.backend.administration.entity.PlanStatus;
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
            EnumSet.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended);

    @Autowired private TenantRepository tenants;
    @Autowired private SaasPlanRepository plans;
    @Autowired private TenantSubscriptionRepository subscriptions;
    @Autowired private SubscriptionInvoiceRepository invoices;

    @Test
    void currentQueriesAreTenantQualifiedAndLockedVariantReturnsSameRow() {
        SaasPlan plan = persistPlan();
        Tenant firstTenant = persistTenant();
        Tenant secondTenant = persistTenant();
        TenantSubscription first = persist(firstTenant.getId(), plan.getId(), TenantSubscriptionStatus.active);
        persist(secondTenant.getId(), plan.getId(), TenantSubscriptionStatus.suspended);

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
        persist(tenant.getId(), plan.getId(), TenantSubscriptionStatus.cancelled);
        persist(tenant.getId(), plan.getId(), TenantSubscriptionStatus.active);

        assertThatThrownBy(() -> persist(
                        tenant.getId(), plan.getId(), TenantSubscriptionStatus.suspended))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void basicSeedUsesQuetzalesAndUnlimitedEmployeesAndBranches() {
        SaasPlan basic = plans.findByCode("basic").orElseThrow();
        assertThat(basic.getMonthlyQuetzales()).isEqualByComparingTo("199.00");
        assertThat(basic.getMaxEmployees()).isNull();
        assertThat(basic.getMaxBranches()).isNull();
        assertThat(basic.getStatus()).isEqualTo(PlanStatus.active);
        assertThat(basic.getCapabilities()).contains("inventory", "pos", "traceability.lots");
    }

    @Test
    void invoiceSnapshotsAreTenantScopedAndKeepAddonPriceDetails() {
        SaasPlan plan = persistPlan();
        Tenant firstTenant = persistTenant();
        Tenant secondTenant = persistTenant();
        TenantSubscription subscription = persist(firstTenant.getId(), plan.getId(), TenantSubscriptionStatus.active);
        SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                .subscriptionId(subscription.getId()).cycleStart(subscription.getCurrentPeriodStart())
                .cycleEnd(subscription.getCurrentPeriodEnd()).addonCodes(List.of("advanced_reports"))
                .baseQuetzales(new BigDecimal("199.00")).totalQuetzales(new BigDecimal("298.00"))
                .addonLinesJson("[{\"code\":\"advanced_reports\",\"name\":\"Reportes avanzados\",\"amountQuetzales\":99}]").build();
        invoice.setTenantId(firstTenant.getId());
        invoices.saveAndFlush(invoice);
        assertThat(invoices.findByTenantIdAndSubscriptionIdAndCycleStart(
                firstTenant.getId(), subscription.getId(), subscription.getCurrentPeriodStart())).isPresent();
        assertThat(invoices.findByTenantIdAndSubscriptionIdOrderByCycleStartDesc(
                secondTenant.getId(), subscription.getId())).isEmpty();
    }

    @Test
    void invoiceCannotReferenceSubscriptionFromAnotherTenant() {
        SaasPlan plan = persistPlan();
        Tenant owner = persistTenant();
        Tenant outsider = persistTenant();
        TenantSubscription subscription = persist(owner.getId(), plan.getId(), TenantSubscriptionStatus.active);
        SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                .subscriptionId(subscription.getId()).cycleStart(subscription.getCurrentPeriodStart())
                .cycleEnd(subscription.getCurrentPeriodEnd()).baseQuetzales(BigDecimal.ONE)
                .totalQuetzales(BigDecimal.ONE).build();
        invoice.setTenantId(outsider.getId());
        assertThatThrownBy(() -> invoices.saveAndFlush(invoice))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoiceCycleCanOnlyBeRecordedOnce() {
        SaasPlan plan = persistPlan();
        Tenant tenant = persistTenant();
        TenantSubscription subscription = persist(tenant.getId(), plan.getId(), TenantSubscriptionStatus.active);
        SubscriptionInvoice first = SubscriptionInvoice.builder()
                .subscriptionId(subscription.getId()).cycleStart(subscription.getCurrentPeriodStart())
                .cycleEnd(subscription.getCurrentPeriodEnd()).baseQuetzales(BigDecimal.ONE)
                .totalQuetzales(BigDecimal.ONE).build();
        first.setTenantId(tenant.getId());
        invoices.saveAndFlush(first);
        SubscriptionInvoice duplicate = SubscriptionInvoice.builder()
                .subscriptionId(subscription.getId()).cycleStart(subscription.getCurrentPeriodStart())
                .cycleEnd(subscription.getCurrentPeriodEnd()).baseQuetzales(BigDecimal.ONE)
                .totalQuetzales(BigDecimal.ONE).build();
        duplicate.setTenantId(tenant.getId());
        assertThatThrownBy(() -> invoices.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TenantSubscription persist(UUID tenantId, UUID planId, TenantSubscriptionStatus status) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TenantSubscription subscription = TenantSubscription.builder()
                .planId(planId)
                .status(status)
                .currentPeriodStart(start)
                .currentPeriodEnd(start.plus(30, ChronoUnit.DAYS))
                .startedAt(start)
                .addonCodes(List.of())
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
                .maxEmployees(1)
                .monthlyQuetzales(BigDecimal.ONE)
                .capabilities(List.of("pos"))
                .status(PlanStatus.active)
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
