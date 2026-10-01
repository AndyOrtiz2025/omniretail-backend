package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.shared.security.SaasCapability;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultTenantEntitlementResolverTest {
    @Mock private TenantSubscriptionRepository subscriptions;
    @Mock private SaasPlanRepository plans;
    @InjectMocks private DefaultTenantEntitlementResolver resolver;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private static final List<TenantSubscriptionStatus> CURRENT =
            List.of(TenantSubscriptionStatus.active, TenantSubscriptionStatus.suspended);
    private static final List<String> BASE = List.of("inventory", "purchasing", "receiving", "pos",
            "traceability.lots", "traceability.expiration", "traceability.serials");

    @Test
    void basicGrantsExactlySevenCapabilitiesAndIgnoresUnknownKeys() {
        setup(TenantSubscriptionStatus.active, PlanStatus.active, List.of());
        var entitlements = resolver.resolve(tenantId);
        assertThat(entitlements.subscriptionActive()).isTrue();
        assertThat(entitlements.planActive()).isTrue();
        assertThat(entitlements.capabilities()).containsExactlyInAnyOrder(SaasCapability.inventory,
                SaasCapability.purchasing, SaasCapability.receiving, SaasCapability.pos,
                SaasCapability.traceabilityLots, SaasCapability.traceabilityExpiration, SaasCapability.traceabilitySerials);
        verify(subscriptions).findByTenantIdAndStatusIn(tenantId, CURRENT);
    }

    @Test
    void ecommerceAddonGrantsEcommerceAndDeliveryOnly() {
        setup(TenantSubscriptionStatus.active, PlanStatus.active, List.of("ecommerce_delivery"));
        assertThat(resolver.resolve(tenantId).capabilities()).hasSize(9)
                .contains(SaasCapability.ecommerce, SaasCapability.delivery)
                .doesNotContain(SaasCapability.advancedReports, SaasCapability.catalogKits);
    }

    @Test
    void reportsAddonGrantsAdvancedReportsOnly() {
        setup(TenantSubscriptionStatus.active, PlanStatus.active, List.of("advanced_reports"));
        assertThat(resolver.resolve(tenantId).capabilities()).hasSize(8).contains(SaasCapability.advancedReports)
                .doesNotContain(SaasCapability.ecommerce, SaasCapability.delivery);
    }

    @Test
    void missingSubscriptionFailsClosedForRequestedTenant() {
        assertThat(resolver.resolve(tenantId).subscriptionActive()).isFalse();
        assertThat(resolver.resolve(tenantId).capabilities()).isEmpty();
        verify(subscriptions, org.mockito.Mockito.times(2)).findByTenantIdAndStatusIn(tenantId, CURRENT);
    }

    @Test
    void suspendedSubscriptionDoesNotGrantCapabilities() {
        setup(TenantSubscriptionStatus.suspended, PlanStatus.active, List.of("advanced_reports"));
        var entitlements = resolver.resolve(tenantId);
        assertThat(entitlements.subscriptionActive()).isFalse();
        assertThat(entitlements.capabilities()).isEmpty();
    }

    @Test
    void archivedPlanDoesNotGrantCapabilities() {
        setup(TenantSubscriptionStatus.active, PlanStatus.archived, List.of("ecommerce_delivery"));
        var entitlements = resolver.resolve(tenantId);
        assertThat(entitlements.planActive()).isFalse();
        assertThat(entitlements.capabilities()).isEmpty();
    }

    @Test
    void anotherTenantCannotInheritTheFirstTenantsSubscription() {
        setup(TenantSubscriptionStatus.active, PlanStatus.active, List.of("advanced_reports"));
        assertThat(resolver.resolve(tenantId).capabilities()).isNotEmpty();
        UUID otherTenant = UUID.randomUUID();
        assertThat(resolver.resolve(otherTenant).capabilities()).isEmpty();
        verify(subscriptions).findByTenantIdAndStatusIn(otherTenant, CURRENT);
    }

    @Test
    void missingPlanFailsClosedEvenForActiveSubscription() {
        when(subscriptions.findByTenantIdAndStatusIn(tenantId, CURRENT)).thenReturn(Optional.of(
                TenantSubscription.builder().planId(planId).status(TenantSubscriptionStatus.active).build()));
        var entitlements = resolver.resolve(tenantId);
        assertThat(entitlements.subscriptionActive()).isTrue();
        assertThat(entitlements.planActive()).isFalse();
        assertThat(entitlements.capabilities()).isEmpty();
    }

    @Test
    void cancelledSubscriptionIsNotConsideredCurrent() {
        var entitlements = resolver.resolve(tenantId);
        assertThat(entitlements.subscriptionActive()).isFalse();
        verify(subscriptions).findByTenantIdAndStatusIn(tenantId, CURRENT);
        org.mockito.Mockito.verifyNoInteractions(plans);
    }
    private void setup(TenantSubscriptionStatus status, PlanStatus planStatus, List<String> addons) {
        var subscription = TenantSubscription.builder().planId(planId).status(status).addonCodes(addons).build();
        subscription.setTenantId(tenantId);
        when(subscriptions.findByTenantIdAndStatusIn(tenantId, CURRENT)).thenReturn(Optional.of(subscription));
        var keys = new java.util.ArrayList<>(BASE);
        keys.add("unknown.capability");
        when(plans.findById(planId)).thenReturn(Optional.of(SaasPlan.builder().status(planStatus).capabilities(keys).build()));
    }
}
