package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La ubicacion asignada se lee como escalar: refleja una actualizacion SQL nativa hecha en la misma
 * transaccion aunque la entidad de configuracion ya este gestionada, y los cambios pendientes del propio
 * contexto de persistencia (la consulta los vacia antes de ejecutarse).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryOperationalLocationAssignedReadTest {

    @Autowired private InventoryOperationalLocationService operationalLocations;
    @Autowired private ProductInventorySettingsRepository settingsRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void readsTheValueWrittenByANativeUpdateEvenWhenTheEntityIsAlreadyManaged() {
        Fixture fixture = fixture();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UUID seen = transaction.execute(status -> {
            ProductInventorySettings managed = settingsRepository
                    .findByTenantIdAndBranchIdAndProductId(
                            fixture.tenantId(), fixture.branchId(), fixture.productId())
                    .orElseThrow();
            assertThat(managed.getDefaultLocationId()).isNull();

            int rows = settingsRepository.assignIfUnassigned(
                    fixture.tenantId(), fixture.branchId(), fixture.productId(), fixture.location());

            assertThat(rows).isOne();
            // Prueba de que el escenario existe: la entidad gestionada conserva el valor anterior.
            assertThat(managed.getDefaultLocationId()).isNull();
            return operationalLocations.assignedLocation(
                    fixture.tenantId(), fixture.branchId(), fixture.productId());
        });

        assertThat(seen).isEqualTo(fixture.location());
    }

    @Test
    void seesTheChangesPendingInTheSamePersistenceContext() {
        Fixture fixture = fixture();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UUID seen = transaction.execute(status -> {
            ProductInventorySettings managed = settingsRepository
                    .findByTenantIdAndBranchIdAndProductId(
                            fixture.tenantId(), fixture.branchId(), fixture.productId())
                    .orElseThrow();
            managed.setDefaultLocationId(fixture.location());

            return operationalLocations.assignedLocation(
                    fixture.tenantId(), fixture.branchId(), fixture.productId());
        });

        assertThat(seen).isEqualTo(fixture.location());
    }

    @Test
    void returnsNullWithoutAnAssignmentAndWithoutAConfigurationRow() {
        Fixture fixture = fixture();
        jdbc.update(
                "DELETE FROM product_inventory_settings WHERE tenant_id = ? AND product_id = ?",
                fixture.tenantId(), fixture.productId());

        assertThat(operationalLocations.assignedLocation(
                        fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isNull();
        // Y sin asignar con la fila presente (default_location_id nulo).
        jdbc.update(
                "INSERT INTO product_inventory_settings (tenant_id, branch_id, product_id, min_stock) "
                        + "VALUES (?, ?, ?, 0)",
                fixture.tenantId(), fixture.branchId(), fixture.productId());
        assertThat(operationalLocations.assignedLocation(
                        fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isNull();
    }

    private Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbc.update(
                "INSERT INTO business_capabilities_configs "
                        + "(id, tenant_id, preset, supports_multiple_locations) VALUES (?, ?, 'custom', true)",
                UUID.randomUUID(), tenantId);
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "categoria-" + suffix);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbc.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, product_type, "
                        + "tracking_stock) VALUES (?, ?, ?, ?, ?, ?, 'physical', true)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'Estante', 'warehouse', 'active')",
                location, tenantId, branchId, "LOC-" + location);
        jdbc.update(
                "INSERT INTO product_inventory_settings (tenant_id, branch_id, product_id, min_stock) "
                        + "VALUES (?, ?, ?, 0)",
                tenantId, branchId, productId);
        return new Fixture(tenantId, branchId, productId, location);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID location) {}
}
