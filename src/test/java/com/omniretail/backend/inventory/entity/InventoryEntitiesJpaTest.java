package com.omniretail.backend.inventory.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class InventoryEntitiesJpaTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsValidInventoryBalance() {
        Fixture fixture = createFixture();
        InventoryBalance balance = balance(fixture, fixture.firstBranchId(), "10.000", "2.000");

        entityManager.persist(balance);
        entityManager.flush();
        entityManager.clear();

        InventoryBalance reloaded = entityManager.find(InventoryBalance.class, balance.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getQuantity()).isEqualByComparingTo("10.000");
        assertThat(reloaded.getReservedQuantity()).isEqualByComparingTo("2.000");
    }

    @Test
    void rejectsNegativeQuantity() {
        Fixture fixture = createFixture();
        InventoryBalance balance = balance(fixture, fixture.firstBranchId(), "-0.001", "0.000");

        assertThatThrownBy(() -> persistAndFlush(balance)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsNegativeReservedQuantity() {
        Fixture fixture = createFixture();
        InventoryBalance balance = balance(fixture, fixture.firstBranchId(), "1.000", "-0.001");

        assertThatThrownBy(() -> persistAndFlush(balance)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsReservedQuantityGreaterThanQuantity() {
        Fixture fixture = createFixture();
        InventoryBalance balance = balance(fixture, fixture.firstBranchId(), "1.000", "1.001");

        assertThatThrownBy(() -> persistAndFlush(balance)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsDuplicateLogicalBalanceWhenLocationIsNull() {
        Fixture fixture = createFixture();
        persistAndFlush(balance(fixture, fixture.firstBranchId(), "1.000", "0.000"));

        InventoryBalance duplicate = balance(fixture, fixture.firstBranchId(), "2.000", "0.000");
        assertThatThrownBy(() -> persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void allowsSameProductInDifferentBranches() {
        Fixture fixture = createFixture();
        InventoryBalance first = balance(fixture, fixture.firstBranchId(), "1.000", "0.000");
        InventoryBalance second = balance(fixture, fixture.secondBranchId(), "2.000", "0.000");

        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
    }

    @Test
    void persistsValidInventoryMovement() {
        Fixture fixture = createFixture();
        InventoryMovement movement = movement(fixture, InventoryMovementType.in, "3.500");

        entityManager.persist(movement);
        entityManager.flush();
        entityManager.clear();

        InventoryMovement reloaded = entityManager.find(InventoryMovement.class, movement.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getType()).isEqualTo(InventoryMovementType.in);
        assertThat(reloaded.getQuantity()).isEqualByComparingTo("3.500");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsInventoryMovementWithZeroQuantity() {
        Fixture fixture = createFixture();
        InventoryMovement movement = movement(fixture, InventoryMovementType.out, "0.000");

        assertThatThrownBy(() -> persistAndFlush(movement)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsInventoryMovementWithNegativeQuantity() {
        Fixture fixture = createFixture();
        InventoryMovement movement = movement(fixture, InventoryMovementType.out, "-0.001");

        assertThatThrownBy(() -> persistAndFlush(movement)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void persistsEveryInventoryMovementType() {
        Fixture fixture = createFixture();
        List<InventoryMovement> movements = new ArrayList<>();

        for (InventoryMovementType type : InventoryMovementType.values()) {
            InventoryMovement movement = movement(fixture, type, "1.000");
            movements.add(movement);
            entityManager.persist(movement);
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(movements)
                .extracting(movement -> entityManager
                        .find(InventoryMovement.class, movement.getId())
                        .getType())
                .containsExactly(
                        InventoryMovementType.in,
                        InventoryMovementType.out,
                        InventoryMovementType.transfer);
    }

    private void persistAndFlush(Object entity) {
        entityManager.persist(entity);
        entityManager.flush();
    }

    private InventoryBalance balance(
            Fixture fixture, UUID branchId, String quantity, String reservedQuantity) {
        InventoryBalance balance = InventoryBalance.builder()
                .branchId(branchId)
                .productId(fixture.productId())
                .quantity(new BigDecimal(quantity))
                .reservedQuantity(new BigDecimal(reservedQuantity))
                .build();
        balance.setTenantId(fixture.tenantId());
        return balance;
    }

    private InventoryMovement movement(
            Fixture fixture, InventoryMovementType type, String quantity) {
        return InventoryMovement.builder()
                .tenantId(fixture.tenantId())
                .branchId(fixture.firstBranchId())
                .productId(fixture.productId())
                .type(type)
                .reason("Movimiento de prueba")
                .quantity(new BigDecimal(quantity))
                .build();
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID firstBranchId = UUID.randomUUID();
        UUID secondBranchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """,
                firstBranchId,
                tenantId,
                "MAIN-" + suffix,
                "Principal " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'warehouse', 'active')
                """,
                secondBranchId,
                tenantId,
                "WH-" + suffix,
                "Bodega " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, ?, ?, 'active')
                """,
                categoryId,
                tenantId,
                "Categoria " + suffix,
                "categoria-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', false, 'active')
                """,
                unitId,
                tenantId,
                "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);

        return new Fixture(tenantId, firstBranchId, secondBranchId, productId);
    }

    private record Fixture(
            UUID tenantId, UUID firstBranchId, UUID secondBranchId, UUID productId) {}
}
