package com.omniretail.backend.catalog.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.catalog.repository.ProductAttributeValueRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
class CatalogPricingAttributesJpaTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductAttributeValueRepository valueRepository;

    @Test
    void attributeCodeIsCaseInsensitiveUniqueInsideTenant() {
        Fixture fixture = fixture();
        insertDefinition(UUID.randomUUID(), fixture.tenant(), "color", "active", "TEXT");

        assertThatThrownBy(() -> insertDefinition(
                        UUID.randomUUID(), fixture.tenant(), "COLOR", "active", "TEXT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameAttributeCodeIsAllowedInDifferentTenants() {
        Fixture first = fixture();
        Fixture second = fixture();

        insertDefinition(UUID.randomUUID(), first.tenant(), "color", "active", "TEXT");
        insertDefinition(UUID.randomUUID(), second.tenant(), "COLOR", "active", "TEXT");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM attribute_definitions WHERE lower(code) = 'color'",
                Long.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void productValueUniqueConstraintRejectsDuplicateDefinition() {
        Fixture fixture = fixture();
        UUID definition = UUID.randomUUID();
        insertDefinition(definition, fixture.tenant(), "color", "active", "TEXT");
        insertValue(UUID.randomUUID(), fixture, definition, "red");

        assertThatThrownBy(() -> insertValue(UUID.randomUUID(), fixture, definition, "blue"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void replacementDeleteRemovesOnlyValuesWhoseDefinitionsAreActive() {
        Fixture fixture = fixture();
        UUID active = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        insertDefinition(active, fixture.tenant(), "active", "active", "TEXT");
        insertDefinition(archived, fixture.tenant(), "archived", "archived", "TEXT");
        insertValue(UUID.randomUUID(), fixture, active, "current");
        insertValue(UUID.randomUUID(), fixture, archived, "historical");

        assertThat(valueRepository.deleteActiveValues(fixture.tenant(), fixture.product())).isOne();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM product_attribute_values
                WHERE tenant_id = ? AND product_id = ? AND attribute_definition_id = ?
                """, Long.class, fixture.tenant(), fixture.product(), active)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM product_attribute_values
                WHERE tenant_id = ? AND product_id = ? AND attribute_definition_id = ?
                """, Long.class, fixture.tenant(), fixture.product(), archived)).isOne();
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Attributes JPA', ?)",
                tenant, "attributes-jpa-" + tenant);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     sale_price, status, tracking_stock)
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, 10.00, 'published', true)
                """, product, tenant, "SKU-" + product, category, unit);
        return new Fixture(tenant, product);
    }

    private void insertDefinition(UUID id, UUID tenant, String code, String status, String type) {
        jdbc.update("""
                INSERT INTO attribute_definitions (id, tenant_id, code, name, data_type, status)
                VALUES (?, ?, ?, 'Atributo', ?, ?)
                """, id, tenant, code, type, status);
    }

    private void insertValue(UUID id, Fixture fixture, UUID definition, String value) {
        jdbc.update("""
                INSERT INTO product_attribute_values
                    (id, tenant_id, product_id, attribute_definition_id, value_string)
                VALUES (?, ?, ?, ?, ?)
                """, id, fixture.tenant(), fixture.product(), definition, value);
    }

    private record Fixture(UUID tenant, UUID product) {}
}
