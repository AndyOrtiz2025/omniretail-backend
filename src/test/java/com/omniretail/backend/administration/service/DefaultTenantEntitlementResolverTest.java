package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unitario: sin contexto de Spring ni Testcontainers. */
class DefaultTenantEntitlementResolverTest {

    @Test
    void defaultResolverGrantsAllElevenCapabilities() {
        TenantEntitlements entitlements = new DefaultTenantEntitlementResolver().resolve(UUID.randomUUID());

        assertThat(entitlements.subscriptionActive()).isTrue();
        assertThat(entitlements.planActive()).isTrue();
        assertThat(entitlements.capabilities()).hasSize(11).containsExactlyInAnyOrder(SaasCapability.values());
    }
}
