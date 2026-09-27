package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryStockServiceTest {

    @Autowired
    private InventoryStockService inventoryStockService;

    @Autowired
    private InventoryBalanceRepository inventoryBalanceRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deductStockReducesPhysicalQuantityAndCreatesOutMovement() {
        Fixture fixture = createFixture("10.000", "2.000");

        InventoryMovement created = inventoryStockService.deductStock(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("3.000"));

        InventoryBalance balance = inventoryBalanceRepository
                .findById(fixture.balanceId())
                .orElseThrow();
        InventoryMovement movement = inventoryMovementRepository
                .findById(created.getId())
                .orElseThrow();
        assertThat(balance.getQuantity()).isEqualByComparingTo("7.000");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("2.000");
        assertThat(movement.getType()).isEqualTo(InventoryMovementType.out);
        assertThat(movement.getQuantity()).isEqualByComparingTo("3.000");
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("10.000");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("7.000");
        assertThat(movement.getReason()).isEqualTo("Deducción de inventario");
        assertThat(movement.getFromLocationId()).isNull();
        assertThat(movement.getToLocationId()).isNull();
        assertThat(movement.getReferenceType()).isNull();
        assertThat(movement.getReferenceId()).isNull();
        assertThat(movement.getPerformedByUserId()).isNull();
        assertThat(countMovements(fixture)).isOne();
    }

    @Test
    void reservedStockCannotBeConsumedByImmediateDeduction() {
        Fixture fixture = createFixture("10.000", "8.000");

        assertInsufficientStock(() -> inventoryStockService.deductStock(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("3.000")));

        assertBalance(fixture, "10.000", "8.000");
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void missingBalanceReturnsInsufficientStock() {
        Fixture fixture = createFixtureWithoutBalance();

        assertInsufficientStock(() -> inventoryStockService.deductStock(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), BigDecimal.ONE));

        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void nullQuantityIsRejected() {
        Fixture fixture = createFixture("10.000", "0.000");

        assertInvalidQuantity(() -> inventoryStockService.deductStock(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), null));

        assertBalance(fixture, "10.000", "0.000");
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void zeroQuantityIsRejected() {
        Fixture fixture = createFixture("10.000", "0.000");

        assertInvalidQuantity(() -> inventoryStockService.deductStock(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), BigDecimal.ZERO));

        assertBalance(fixture, "10.000", "0.000");
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void negativeQuantityIsRejected() {
        Fixture fixture = createFixture("10.000", "0.000");

        assertInvalidQuantity(() -> inventoryStockService.deductStock(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("-1.000")));

        assertBalance(fixture, "10.000", "0.000");
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void tenantIsolationPreventsCrossTenantDeduction() {
        Fixture tenantA = createFixture("5.000", "0.000");
        Fixture tenantB = createFixture("7.000", "1.000");

        assertInsufficientStock(() -> inventoryStockService.deductStock(
                tenantA.tenantId(), tenantB.branchId(), tenantB.productId(), BigDecimal.ONE));

        assertBalance(tenantB, "7.000", "1.000");
        assertThat(countMovements(tenantB)).isZero();
    }

    @Test
    void commandMetadataIsStoredInMovement() {
        Fixture fixture = createFixture("10.000", "0.000");
        UUID referenceId = UUID.randomUUID();
        DeductStockCommand command = new DeductStockCommand(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                new BigDecimal("2.500"),
                "Venta POS",
                "POS_SALE",
                referenceId,
                fixture.userId());

        InventoryMovement created = inventoryStockService.deductStock(command);

        InventoryMovement movement = inventoryMovementRepository
                .findById(created.getId())
                .orElseThrow();
        assertThat(movement.getReason()).isEqualTo("Venta POS");
        assertThat(movement.getReferenceType()).isEqualTo("POS_SALE");
        assertThat(movement.getReferenceId()).isEqualTo(referenceId);
        assertThat(movement.getPerformedByUserId()).isEqualTo(fixture.userId());
        assertThat(countMovements(fixture)).isOne();
    }

    private void assertBalance(Fixture fixture, String quantity, String reservedQuantity) {
        InventoryBalance balance = inventoryBalanceRepository
                .findById(fixture.balanceId())
                .orElseThrow();
        assertThat(balance.getQuantity()).isEqualByComparingTo(quantity);
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo(reservedQuantity);
    }

    private static void assertInsufficientStock(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(exception.getMessage()).isEqualTo("Stock insuficiente.");
                });
    }

    private static void assertInvalidQuantity(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("INVALID_STOCK_QUANTITY");
                    assertThat(exception.getMessage()).isEqualTo("La cantidad debe ser mayor que cero.");
                });
    }

    private long countMovements(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
        return count == null ? 0 : count;
    }

    private Fixture createFixture(String quantity, String reservedQuantity) {
        Fixture fixture = createFixtureWithoutBalance();
        UUID balanceId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, NULL, ?, ?)
                """,
                balanceId,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                new BigDecimal(quantity),
                new BigDecimal(reservedQuantity));
        return new Fixture(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                fixture.userId(),
                balanceId);
    }

    private Fixture createFixtureWithoutBalance() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
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
                branchId,
                tenantId,
                "MAIN-" + suffix,
                "Principal " + suffix);
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
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
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
        jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, ?, ?, 'employee', 'active', ?)
                """,
                userId,
                tenantId,
                "Usuario " + suffix,
                "usuario-" + suffix + "@example.com",
                branchId);

        return new Fixture(tenantId, branchId, productId, userId, null);
    }

    private record Fixture(
            UUID tenantId, UUID branchId, UUID productId, UUID userId, UUID balanceId) {}
}
