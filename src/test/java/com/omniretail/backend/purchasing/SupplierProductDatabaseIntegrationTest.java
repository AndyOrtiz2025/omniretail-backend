package com.omniretail.backend.purchasing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class SupplierProductDatabaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void databaseDefendsLogicalUniqueness() {
        Fixture fixture = fixture();
        insertSupplierProduct(fixture, fixture.firstSupplier(), false);

        assertThatThrownBy(() -> insertSupplierProduct(fixture, fixture.firstSupplier(), false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDefendsSingleActivePreferred() {
        Fixture fixture = fixture();
        insertSupplierProduct(fixture, fixture.firstSupplier(), true);

        assertThatThrownBy(() -> insertSupplierProduct(fixture, fixture.secondSupplier(), true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDefendsDuplicateTierQuantity() {
        Fixture fixture = fixture();
        UUID first = insertSupplierProduct(fixture, fixture.firstSupplier(), false);

        jdbc.update("""
                INSERT INTO supplier_cost_tiers (tenant_id, supplier_product_id, min_quantity, unit_cost)
                VALUES (?, ?, 1, 0)
                """, fixture.tenant(), first);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO supplier_cost_tiers (tenant_id, supplier_product_id, min_quantity, unit_cost)
                VALUES (?, ?, 1.000, 2)
                """, fixture.tenant(), first)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDefendsPositiveFactor() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO supplier_products
                    (tenant_id, supplier_id, product_id, purchase_unit_id, purchase_to_base_factor,
                     last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, 0, 0, 0, 1)
                """, fixture.tenant(), fixture.secondSupplier(), fixture.secondProduct(), fixture.unit()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,-0.01,0,1", "1,0,-1,1", "1,0,0,0"})
    void databaseDefendsCostLeadTimeAndMoqChecks(String numericValues) {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO supplier_products
                    (tenant_id, supplier_id, product_id, purchase_unit_id, purchase_to_base_factor,
                     last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, %s)
                """.formatted(numericValues), fixture.tenant(), fixture.firstSupplier(),
                fixture.firstProduct(), fixture.unit())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,1", "1,-0.01"})
    void databaseDefendsTierChecks(String numericValues) {
        Fixture fixture = fixture();
        UUID supplierProduct = insertSupplierProduct(fixture, fixture.firstSupplier(), false);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO supplier_cost_tiers (tenant_id, supplier_product_id, min_quantity, unit_cost)
                VALUES (?, ?, %s)
                """.formatted(numericValues), fixture.tenant(), supplierProduct))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDefendsMasterForeignKeys() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO supplier_products
                    (tenant_id, supplier_id, product_id, purchase_unit_id, purchase_to_base_factor,
                     last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, 1, 0, 0, 1)
                """, fixture.tenant(), UUID.randomUUID(), fixture.firstProduct(), fixture.unit()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID firstProduct = UUID.randomUUID();
        UUID secondProduct = UUID.randomUUID();
        UUID firstSupplier = UUID.randomUUID();
        UUID secondSupplier = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'DB purchasing', ?)", tenant, "db-p-" + suffix);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Categoria', ?)",
                category,
                tenant,
                "cat-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')
                """, unit, tenant, "U-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Primero', ?, ?), (?, ?, ?, 'Segundo', ?, ?)
                """, firstProduct, tenant, "A-" + suffix, category, unit,
                secondProduct, tenant, "B-" + suffix, category, unit);
        jdbc.update("""
                INSERT INTO suppliers (id, tenant_id, name, status)
                VALUES (?, ?, 'Primero', 'active'), (?, ?, 'Segundo', 'active')
                """, firstSupplier, tenant, secondSupplier, tenant);
        return new Fixture(tenant, unit, firstProduct, secondProduct, firstSupplier, secondSupplier);
    }

    private UUID insertSupplierProduct(Fixture fixture, UUID supplier, boolean preferred) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, purchase_unit_id, purchase_to_base_factor,
                     last_cost, lead_time_days, minimum_order_quantity, preferred)
                VALUES (?, ?, ?, ?, ?, 1, 0, 0, 1, ?)
                """, id, fixture.tenant(), supplier, fixture.firstProduct(), fixture.unit(), preferred);
        return id;
    }

    private record Fixture(
            UUID tenant,
            UUID unit,
            UUID firstProduct,
            UUID secondProduct,
            UUID firstSupplier,
            UUID secondSupplier) {}
}
