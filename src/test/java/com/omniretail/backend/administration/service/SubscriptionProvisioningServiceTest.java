package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Aprovisionamiento interno de complementos contra PostgreSQL: idempotente por operationId, dirigido a un solo
 * tenant, y con los entitlements reales resultantes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SubscriptionProvisioningServiceTest {

    @Autowired private SubscriptionProvisioningService provisioning;
    @Autowired private TenantEntitlementResolver entitlements;
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theRunnerDoesNotExistUnlessProvisioningIsExplicitlyEnabled() {
        assertThat(context.containsBean("subscriptionProvisioningRunner")).isFalse();
    }

    @Test
    void appliesTheAddonsOfOnlyTheTargetTenantAndTheEntitlementsReflectIt() {
        UUID demo = tenantWithBasicPlan();
        UUID other = tenantWithBasicPlan();
        assertThat(entitlements.resolve(demo).capabilities()).doesNotContain(SaasCapability.ecommerce);

        SubscriptionProvisioningService.Result result = provisioning.applyOnce(
                demo, operation(), List.of("ecommerce_delivery", "advanced_reports"));

        assertThat(result).isEqualTo(SubscriptionProvisioningService.Result.APPLIED);
        assertThat(entitlements.resolve(demo).capabilities())
                .contains(SaasCapability.ecommerce, SaasCapability.delivery, SaasCapability.advancedReports);
        // El otro negocio no cambia: los modulos opcionales no se activan globalmente.
        assertThat(addons(other)).isEmpty();
        assertThat(entitlements.resolve(other).capabilities())
                .doesNotContain(SaasCapability.ecommerce, SaasCapability.delivery, SaasCapability.advancedReports);
    }

    @Test
    void repeatingTheSameOperationNeverRewritesAnything() {
        UUID tenant = tenantWithBasicPlan();
        String operation = operation();
        provisioning.applyOnce(tenant, operation, List.of("advanced_reports"));
        // Alguien cambia el valor a mano y el servidor reinicia con la misma configuracion.
        jdbc.update("UPDATE tenant_subscriptions SET addon_codes = ARRAY[]::text[] WHERE tenant_id = ?", tenant);

        SubscriptionProvisioningService.Result result =
                provisioning.applyOnce(tenant, operation, List.of("advanced_reports", "ecommerce_delivery"));

        assertThat(result).isEqualTo(SubscriptionProvisioningService.Result.ALREADY_APPLIED);
        assertThat(addons(tenant)).isEmpty();
        assertThat(ledgerRows(operation)).isOne();
    }

    @Test
    void aNewOperationIdAppliesTheNewConfiguration() {
        UUID tenant = tenantWithBasicPlan();
        provisioning.applyOnce(tenant, operation(), List.of("advanced_reports"));

        provisioning.applyOnce(tenant, operation(), List.of("ecommerce_delivery"));

        assertThat(addons(tenant)).containsExactly("ecommerce_delivery");
    }

    @Test
    void anUnknownCodeFailsWithoutConsumingTheOperationId() {
        UUID tenant = tenantWithBasicPlan();
        String operation = operation();

        assertThatThrownBy(() -> provisioning.applyOnce(tenant, operation, List.of("unknown")))
                .isInstanceOf(BusinessException.class);

        assertThat(ledgerRows(operation)).isZero();
        assertThat(addons(tenant)).isEmpty();
        // Corregida la configuracion, la misma operacion se puede aplicar.
        assertThat(provisioning.applyOnce(tenant, operation, List.of("advanced_reports")))
                .isEqualTo(SubscriptionProvisioningService.Result.APPLIED);
    }

    @Test
    void aTenantWithoutSubscriptionRollsBackTheLedger() {
        UUID tenant = UUID.randomUUID();
        insertTenant(tenant);
        String operation = operation();

        assertThatThrownBy(() -> provisioning.applyOnce(tenant, operation, List.of("advanced_reports")))
                .isInstanceOf(BusinessException.class);

        assertThat(ledgerRows(operation)).isZero();
    }

    @Test
    void anUnknownTenantFailsAndLeavesNoRecord() {
        String operation = operation();

        assertThatThrownBy(() -> provisioning.applyOnce(UUID.randomUUID(), operation, List.of("advanced_reports")))
                .isInstanceOf(RuntimeException.class);

        assertThat(ledgerRows(operation)).isZero();
    }

    @Test
    void anEmptyListClearsTheAddons() {
        UUID tenant = tenantWithBasicPlan();
        provisioning.applyOnce(tenant, operation(), List.of("advanced_reports"));

        provisioning.applyOnce(tenant, operation(), List.of());

        assertThat(addons(tenant)).isEmpty();
    }

    private String operation() {
        return "op-" + UUID.randomUUID();
    }

    private UUID tenantWithBasicPlan() {
        UUID tenant = UUID.randomUUID();
        insertTenant(tenant);
        jdbc.update("""
                INSERT INTO tenant_subscriptions
                    (tenant_id, plan_id, status, started_at, current_period_start, current_period_end, addon_codes)
                SELECT ?, id, 'active', now(), now(), now() + INTERVAL '1 month', ARRAY[]::text[]
                FROM saas_plans WHERE code = 'basic'
                """, tenant);
        return tenant;
    }

    private void insertTenant(UUID tenant) {
        String suffix = tenant.toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenant, "Tenant " + suffix, "tenant-" + suffix);
    }

    private List<String> addons(UUID tenant) {
        String joined = jdbc.queryForObject(
                "SELECT array_to_string(addon_codes, ',') FROM tenant_subscriptions WHERE tenant_id = ?",
                String.class, tenant);
        return joined == null || joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }

    private long ledgerRows(String operation) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription_provisioning_operations WHERE operation_id = ?",
                Long.class, operation);
    }
}
