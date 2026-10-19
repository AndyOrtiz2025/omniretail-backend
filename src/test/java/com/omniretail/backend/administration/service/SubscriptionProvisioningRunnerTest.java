package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionProvisioningRunnerTest {

    private final SubscriptionProvisioningService service = mock(SubscriptionProvisioningService.class);
    private final UUID tenant = UUID.randomUUID();

    private SubscriptionProvisioningRunner runner(String operationId, String tenantId, String addons) {
        return new SubscriptionProvisioningRunner(
                new SubscriptionProvisioningProperties(true, operationId, tenantId, addons), service);
    }

    @Test
    void appliesTheConfiguredAddonsToTheConfiguredTenantOnly() {
        when(service.applyOnce(any(), anyString(), any())).thenReturn(SubscriptionProvisioningService.Result.APPLIED);

        runner("demo-2026-10", tenant.toString(), " ecommerce_delivery , advanced_reports ").run(null);

        verify(service).applyOnce(eq(tenant), eq("demo-2026-10"), eq(List.of("ecommerce_delivery", "advanced_reports")));
    }

    @Test
    void anEmptyAddonListMeansNoAddons() {
        when(service.applyOnce(any(), anyString(), any())).thenReturn(SubscriptionProvisioningService.Result.APPLIED);

        runner("clean-1", tenant.toString(), "").run(null);

        verify(service).applyOnce(tenant, "clean-1", List.of());
    }

    @Test
    void anIncompleteConfigurationStopsTheStartupWithoutTouchingAnything() {
        assertThatThrownBy(() -> runner("", tenant.toString(), "advanced_reports").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operation-id");
        assertThatThrownBy(() -> runner("bad id!", tenant.toString(), "advanced_reports").run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> runner("op-1", "", "advanced_reports").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-id");
        assertThatThrownBy(() -> runner("op-1", "not-a-uuid", "advanced_reports").run(null))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(service);
    }

    @Test
    void propertiesAreDisabledByDefault() {
        SubscriptionProvisioningProperties defaults = new SubscriptionProvisioningProperties(null, null, null, null);

        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.operationId()).isEmpty();
        assertThat(defaults.tenantId()).isEmpty();
    }
}
