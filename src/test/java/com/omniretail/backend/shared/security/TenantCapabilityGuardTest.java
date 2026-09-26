package com.omniretail.backend.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.omniretail.backend.shared.exception.BusinessException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Unitario: sin contexto de Spring ni Testcontainers; el resolver es un lambda falso. */
class TenantCapabilityGuardTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Test
    void allActiveWithCapabilityDoesNotThrow() {
        TenantCapabilityGuard guard = guard(true, true, EnumSet.of(SaasCapability.pos));

        assertThatCode(() -> guard.ensureTenantCapability(TENANT_ID, SaasCapability.pos)).doesNotThrowAnyException();
    }

    @Test
    void inactiveSubscriptionWinsOverInactivePlan() {
        TenantCapabilityGuard guard = guard(false, false, Set.of());

        assertForbidden(guard, "SUBSCRIPTION_INACTIVE", "La suscripción del negocio no está activa.");
    }

    @Test
    void inactivePlanIsForbidden() {
        TenantCapabilityGuard guard = guard(true, false, EnumSet.allOf(SaasCapability.class));

        assertForbidden(guard, "PLAN_INACTIVE", "El plan del negocio no está activo.");
    }

    @Test
    void missingCapabilityIsForbidden() {
        TenantCapabilityGuard guard = guard(true, true, EnumSet.of(SaasCapability.inventory));

        assertForbidden(guard, "CAPABILITY_REQUIRED", "Esta función no está incluida en tu plan actual.");
    }

    @Test
    void advancedReportsKeyMatchesFrontend() {
        assertThat(SaasCapability.advancedReports.getKey()).isEqualTo("reports.advanced");
    }

    private static TenantCapabilityGuard guard(boolean subscriptionActive, boolean planActive,
            Set<SaasCapability> capabilities) {
        return new TenantCapabilityGuard(tenantId -> new TenantEntitlements(subscriptionActive, planActive, capabilities));
    }

    /** Siempre se pide {@code pos}: en cada caso falla antes o por no tenerla. */
    private static void assertForbidden(TenantCapabilityGuard guard, String code, String message) {
        BusinessException ex = catchThrowableOfType(BusinessException.class,
                () -> guard.ensureTenantCapability(TENANT_ID, SaasCapability.pos));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getCode()).isEqualTo(code);
        assertThat(ex.getMessage()).isEqualTo(message);
    }
}
