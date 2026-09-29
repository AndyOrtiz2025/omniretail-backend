package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.dto.AddStockCommand;
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
    void incrementStockIncreasesPhysicalQuantityAndCreatesInMovement() {
        Fixture fixture = createFixture("10.000", "2.000");

        InventoryMovement created = inventoryStockService.incrementStock(
                addStockCommand(fixture, new BigDecimal("3.000"), "Conteo físico"));

        InventoryBalance balance = inventoryBalanceRepository
                .findById(fixture.balanceId())
                .orElseThrow();
        InventoryMovement movement = inventoryMovementRepository
                .findById(created.getId())
                .orElseThrow();
        assertThat(balance.getQuantity()).isEqualByComparingTo("13.000");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("2.000");
        assertThat(movement.getType()).isEqualTo(InventoryMovementType.in);
        assertThat(movement.getQuantity()).isEqualByComparingTo("3.000");
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("10.000");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("13.000");
        assertThat(movement.getReason()).isEqualTo("Conteo físico");
        assertThat(movement.getPerformedByUserId()).isEqualTo(fixture.userId());
        assertThat(countMovements(fixture)).isOne();
    }

    @Test
    void incrementStockCreatesMissingBalanceAndInMovement() {
        Fixture fixture = createFixtureWithoutBalance();

        InventoryMovement created = inventoryStockService.incrementStock(
                addStockCommand(fixture, new BigDecimal("5.000"), "Inventario inicial"));

        assertThat(countBalances(fixture)).isOne();
        assertThat(defaultBalanceQuantity(fixture)).isEqualByComparingTo("5.000");
        assertThat(defaultBalanceReservedQuantity(fixture)).isEqualByComparingTo("0.000");
        InventoryMovement movement = inventoryMovementRepository
                .findById(created.getId())
                .orElseThrow();
        assertThat(movement.getType()).isEqualTo(InventoryMovementType.in);
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("0.000");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("5.000");
        assertThat(countMovements(fixture)).isOne();
    }

    @Test
    void incrementStockRejectsZeroQuantityBeforeCreatingBalance() {
        Fixture fixture = createFixtureWithoutBalance();

        assertInvalidQuantity(() -> inventoryStockService.incrementStock(
                addStockCommand(fixture, BigDecimal.ZERO, "Ajuste inválido")));

        assertThat(countBalances(fixture)).isZero();
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void incrementStockRejectsNegativeQuantityBeforeCreatingBalance() {
        Fixture fixture = createFixtureWithoutBalance();

        assertInvalidQuantity(() -> inventoryStockService.incrementStock(
                addStockCommand(fixture, new BigDecimal("-1.000"), "Ajuste inválido")));

        assertThat(countBalances(fixture)).isZero();
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void incrementStockRollsBackBalanceWhenMovementCannotBePersisted() {
        Fixture fixture = createFixture("10.000", "2.000");

        assertThatThrownBy(() -> inventoryStockService.incrementStock(
                        addStockCommand(fixture, BigDecimal.ONE, null)))
                .isInstanceOf(RuntimeException.class);

        assertBalance(fixture, "10.000", "2.000");
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void incrementAtLocationCreatesSpecificBalanceMovementAndPreservesMetadata() {
        Fixture fixture = createFixtureWithoutBalance();
        UUID locationId = createLocation(fixture, fixture.branchId(), "active");
        UUID referenceId = UUID.randomUUID();
        AddStockCommand command = new AddStockCommand(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                new BigDecimal("3.500"),
                "Recepcion de compra",
                "GOODS_RECEIPT",
                referenceId,
                fixture.userId());

        InventoryMovement created = inventoryStockService.incrementStockAtLocation(command, locationId);

        assertThat(locationBalanceQuantity(fixture, locationId)).isEqualByComparingTo("3.500");
        assertThat(locationBalanceReservedQuantity(fixture, locationId)).isEqualByComparingTo("0.000");
        assertThat(countBalances(fixture)).isZero();
        InventoryMovement movement = inventoryMovementRepository.findById(created.getId()).orElseThrow();
        assertThat(movement.getType()).isEqualTo(InventoryMovementType.in);
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("0.000");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("3.500");
        assertThat(movement.getFromLocationId()).isNull();
        assertThat(movement.getToLocationId()).isEqualTo(locationId);
        assertThat(movement.getReason()).isEqualTo("Recepcion de compra");
        assertThat(movement.getReferenceType()).isEqualTo("GOODS_RECEIPT");
        assertThat(movement.getReferenceId()).isEqualTo(referenceId);
        assertThat(movement.getPerformedByUserId()).isEqualTo(fixture.userId());
    }

    @Test
    void incrementAtLocationUpdatesExistingBalanceWithoutChangingReservedQuantity() {
        Fixture fixture = createFixtureWithoutBalance();
        UUID locationId = createLocation(fixture, fixture.branchId(), "active");
        createLocationBalance(fixture, locationId, "10.000", "2.000");

        InventoryMovement movement = inventoryStockService.incrementStockAtLocation(
                addStockCommand(fixture, new BigDecimal("4.000"), "Recepcion parcial"), locationId);

        assertThat(locationBalanceQuantity(fixture, locationId)).isEqualByComparingTo("14.000");
        assertThat(locationBalanceReservedQuantity(fixture, locationId)).isEqualByComparingTo("2.000");
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("10.000");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("14.000");
    }

    @Test
    void defaultAndDifferentLocationBalancesRemainIndependent() {
        Fixture fixture = createFixture("5.000", "1.000");
        UUID firstLocation = createLocation(fixture, fixture.branchId(), "active");
        UUID secondLocation = createLocation(fixture, fixture.branchId(), "active");

        inventoryStockService.incrementStockAtLocation(
                addStockCommand(fixture, new BigDecimal("2.000"), "Primera ubicacion"), firstLocation);
        inventoryStockService.incrementStockAtLocation(
                addStockCommand(fixture, new BigDecimal("7.000"), "Segunda ubicacion"), secondLocation);

        assertBalance(fixture, "5.000", "1.000");
        assertThat(locationBalanceQuantity(fixture, firstLocation)).isEqualByComparingTo("2.000");
        assertThat(locationBalanceQuantity(fixture, secondLocation)).isEqualByComparingTo("7.000");
    }

    @Test
    void incrementAtLocationRejectsInvalidArgumentsBeforeCreatingBalance() {
        Fixture fixture = createFixtureWithoutBalance();
        UUID locationId = createLocation(fixture, fixture.branchId(), "active");

        assertThatThrownBy(() -> inventoryStockService.incrementStockAtLocation(null, locationId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_STOCK_COMMAND"));
        assertThatThrownBy(() -> inventoryStockService.incrementStockAtLocation(
                        addStockCommand(fixture, BigDecimal.ONE, "Sin ubicacion"), null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_STOCK_LOCATION"));
        assertInvalidQuantity(() -> inventoryStockService.incrementStockAtLocation(
                addStockCommand(fixture, BigDecimal.ZERO, "Cantidad cero"), locationId));
        assertInvalidQuantity(() -> inventoryStockService.incrementStockAtLocation(
                addStockCommand(fixture, new BigDecimal("-1.000"), "Cantidad negativa"), locationId));
        assertThat(countLocationBalances(fixture, locationId)).isZero();
    }

    @Test
    void incrementAtLocationEnforcesTenantBranchAndStatus() {
        Fixture fixture = createFixtureWithoutBalance();
        Fixture otherTenant = createFixtureWithoutBalance();
        UUID crossTenantLocation = createLocation(otherTenant, otherTenant.branchId(), "active");
        assertLocationError(
                () -> inventoryStockService.incrementStockAtLocation(
                        addStockCommand(fixture, BigDecimal.ONE, "Cross tenant"), crossTenantLocation),
                HttpStatus.NOT_FOUND,
                "LOCATION_NOT_FOUND");

        UUID otherBranch = createBranch(fixture.tenantId());
        UUID otherBranchLocation = createLocation(fixture, otherBranch, "active");
        assertLocationError(
                () -> inventoryStockService.incrementStockAtLocation(
                        addStockCommand(fixture, BigDecimal.ONE, "Otra sucursal"), otherBranchLocation),
                HttpStatus.BAD_REQUEST,
                "LOCATION_BRANCH_MISMATCH");

        for (String status : new String[] {"inactive", "archived"}) {
            UUID locationId = createLocation(fixture, fixture.branchId(), status);
            assertLocationError(
                    () -> inventoryStockService.incrementStockAtLocation(
                            addStockCommand(fixture, BigDecimal.ONE, "Ubicacion " + status), locationId),
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_NOT_ACTIVE");
        }
    }

    @Test
    void incrementAtLocationRollsBackBalanceWhenMovementCannotBePersisted() {
        Fixture fixture = createFixtureWithoutBalance();
        UUID locationId = createLocation(fixture, fixture.branchId(), "active");
        createLocationBalance(fixture, locationId, "10.000", "2.000");

        assertThatThrownBy(() -> inventoryStockService.incrementStockAtLocation(
                        addStockCommand(fixture, BigDecimal.ONE, null), locationId))
                .isInstanceOf(RuntimeException.class);

        assertThat(locationBalanceQuantity(fixture, locationId)).isEqualByComparingTo("10.000");
        assertThat(locationBalanceReservedQuantity(fixture, locationId)).isEqualByComparingTo("2.000");
        assertThat(countMovements(fixture)).isZero();

        UUID emptyLocation = createLocation(fixture, fixture.branchId(), "active");
        assertThatThrownBy(() -> inventoryStockService.incrementStockAtLocation(
                        addStockCommand(fixture, BigDecimal.ONE, null), emptyLocation))
                .isInstanceOf(RuntimeException.class);
        assertThat(countLocationBalances(fixture, emptyLocation)).isZero();
    }

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

    private static void assertLocationError(
            Runnable operation, HttpStatus status, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
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

    private long countBalances(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
        return count == null ? 0 : count;
    }

    private BigDecimal defaultBalanceQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                SELECT quantity
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
    }

    private BigDecimal defaultBalanceReservedQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                SELECT reserved_quantity
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
    }

    private long countLocationBalances(Fixture fixture, UUID locationId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId);
        return count == null ? 0 : count;
    }

    private BigDecimal locationBalanceQuantity(Fixture fixture, UUID locationId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId);
    }

    private BigDecimal locationBalanceReservedQuantity(Fixture fixture, UUID locationId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT reserved_quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId);
    }

    private void createLocationBalance(
            Fixture fixture, UUID locationId, String quantity, String reservedQuantity) {
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances
                    (tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?::numeric, ?::numeric)
                """,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId,
                quantity,
                reservedQuantity);
    }

    private UUID createLocation(Fixture fixture, UUID branchId, String status) {
        UUID locationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega prueba', 'warehouse', ?)
                """,
                locationId,
                fixture.tenantId(),
                branchId,
                "LOC-" + locationId,
                status);
        return locationId;
    }

    private UUID createBranch(UUID tenantId) {
        UUID branchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Sucursal secundaria', 'store', 'active')
                """,
                branchId,
                tenantId,
                "BR-" + branchId);
        return branchId;
    }

    private static AddStockCommand addStockCommand(
            Fixture fixture, BigDecimal quantity, String reason) {
        return new AddStockCommand(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                quantity,
                reason,
                "MANUAL_ADJUSTMENT",
                UUID.randomUUID(),
                fixture.userId());
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
