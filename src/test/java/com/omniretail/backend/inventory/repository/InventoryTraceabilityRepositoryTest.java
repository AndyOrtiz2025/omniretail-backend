package com.omniretail.backend.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import com.omniretail.backend.inventory.entity.InventoryMovementTrace;
import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
class InventoryTraceabilityRepositoryTest {

    @Autowired private InventoryLotRepository lots;
    @Autowired private InventoryLotBalanceRepository lotBalances;
    @Autowired private InventorySerialRepository serials;
    @Autowired private InventoryMovementTraceRepository movementTraces;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void lotQueriesAreTenantScopedAndLotNumberIsUniquePerProduct() {
        Fixture fixture = createFixture();
        InventoryLot expiring = saveLot(fixture, "LOT-FOUNDATION", LocalDate.of(2027, 1, 15));
        saveLot(fixture, "LOT-LATER", LocalDate.of(2027, 3, 1));

        assertThat(lots.findByTenantIdAndProductIdAndLotNumber(
                        fixture.tenantId(), fixture.productId(), "LOT-FOUNDATION"))
                .get().extracting(InventoryLot::getId).isEqualTo(expiring.getId());
        assertThat(lots.findByTenantIdAndProductIdOrderByExpirationDateAscLotNumberAsc(
                        fixture.tenantId(), fixture.productId()))
                .extracting(InventoryLot::getLotNumber)
                .containsExactly("LOT-FOUNDATION", "LOT-LATER");
        assertThat(lots.findByTenantIdAndProductIdAndExpirationDateBetweenOrderByExpirationDateAscLotNumberAsc(
                        fixture.tenantId(), fixture.productId(),
                        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)))
                .extracting(InventoryLot::getId)
                .containsExactly(expiring.getId());
        assertThat(lots.findByTenantIdAndProductIdAndLotNumber(
                UUID.randomUUID(), fixture.productId(), "LOT-FOUNDATION")).isEmpty();

        assertThatThrownBy(() -> saveLot(
                        fixture, "LOT-FOUNDATION", LocalDate.of(2028, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lotBalanceLookupIsTenantScopedAndNullLocationIsOneLogicalLocation() {
        Fixture fixture = createFixture();
        InventoryLot lot = saveLot(fixture, "LOT-BALANCE", null);
        InventoryLotBalance balance = saveLotBalance(
                fixture, lot.getId(), null, new BigDecimal("10.000"), new BigDecimal("2.000"));
        InventoryLotBalance locatedBalance = saveLotBalance(
                fixture, lot.getId(), fixture.locationId(), new BigDecimal("3.000"), BigDecimal.ZERO);

        assertThat(lotBalances.findForUpdateWithoutLocation(
                        fixture.tenantId(), fixture.branchId(), fixture.productId(), lot.getId()))
                .get().extracting(InventoryLotBalance::getId).isEqualTo(balance.getId());
        assertThat(lotBalances.findForUpdateAtLocation(
                        fixture.tenantId(), fixture.branchId(), fixture.productId(),
                        lot.getId(), fixture.locationId()))
                .get().extracting(InventoryLotBalance::getId).isEqualTo(locatedBalance.getId());
        assertThat(lotBalances.findByTenantBranchAndProduct(
                        fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .extracting(InventoryLotBalance::getId)
                .containsExactlyInAnyOrder(balance.getId(), locatedBalance.getId());
        assertThat(lotBalances.findByTenantBranchAndProduct(
                UUID.randomUUID(), fixture.branchId(), fixture.productId())).isEmpty();

        assertThatThrownBy(() -> saveLotBalance(
                        fixture, lot.getId(), null, BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lotBalanceDatabaseConstraintRejectsReservedQuantityAboveQuantity() {
        Fixture fixture = createFixture();
        InventoryLot lot = saveLot(fixture, "LOT-INVALID-BALANCE", null);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_lot_balances
                            (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                        VALUES (?, ?, ?, NULL, ?, 1.000, 2.000)
                        """,
                        UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), lot.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serialQueriesAreTenantScopedAndSerialNumberIsUniquePerProduct() {
        Fixture fixture = createFixture();
        InventoryLot lot = saveLot(fixture, "LOT-SERIAL", null);
        InventorySerial serial = saveSerial(fixture, lot.getId(), "SERIAL-001");

        assertThat(serials.findByTenantIdAndProductIdAndSerialNumber(
                        fixture.tenantId(), fixture.productId(), "SERIAL-001"))
                .get().extracting(InventorySerial::getId).isEqualTo(serial.getId());
        assertThat(serials.findByTenantIdAndBranchIdAndProductIdAndStatusOrderBySerialNumberAsc(
                        fixture.tenantId(), fixture.branchId(), fixture.productId(),
                        InventorySerialStatus.AVAILABLE))
                .extracting(InventorySerial::getId)
                .containsExactly(serial.getId());
        assertThat(serials.findByTenantIdAndBranchIdAndLocationIdAndStatusOrderBySerialNumberAsc(
                        fixture.tenantId(), fixture.branchId(), fixture.locationId(),
                        InventorySerialStatus.AVAILABLE))
                .extracting(InventorySerial::getId)
                .containsExactly(serial.getId());
        assertThat(serials.findByTenantIdAndProductIdAndSerialNumber(
                UUID.randomUUID(), fixture.productId(), "SERIAL-001")).isEmpty();

        assertThatThrownBy(() -> saveSerial(fixture, lot.getId(), "SERIAL-001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serialCanReferenceLotFromSameTenantAndProduct() {
        Fixture fixture = createFixture();
        InventoryLot lot = saveLot(fixture, "LOT-MATCHING-SCOPE", null);

        InventorySerial serial = saveSerial(fixture, lot.getId(), "SERIAL-MATCHING-SCOPE");

        assertThat(serial.getId()).isNotNull();
        assertThat(serial.getTenantId()).isEqualTo(fixture.tenantId());
        assertThat(serial.getProductId()).isEqualTo(fixture.productId());
        assertThat(serial.getLotId()).isEqualTo(lot.getId());
    }

    @Test
    void serialCannotReferenceLotFromAnotherProductInSameTenant() {
        Fixture fixture = createFixture();
        InventoryLot lot = saveLot(fixture, "LOT-PRODUCT-A", null);
        UUID secondProductId = createProduct(fixture, "B");

        assertThatThrownBy(() -> insertSerial(
                        fixture, secondProductId, lot.getId(), "SERIAL-PRODUCT-B"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serialCannotReferenceLotFromAnotherTenant() {
        Fixture lotOwner = createFixture();
        Fixture serialOwner = createFixture();
        InventoryLot foreignLot = saveLot(lotOwner, "LOT-FOREIGN-TENANT", null);

        assertThatThrownBy(() -> insertSerial(
                        serialOwner, serialOwner.productId(), foreignLot.getId(),
                        "SERIAL-FOREIGN-TENANT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serialWithoutLotIsAllowed() {
        Fixture fixture = createFixture();

        InventorySerial serial = saveSerial(fixture, null, "SERIAL-WITHOUT-LOT");

        assertThat(serial.getId()).isNotNull();
        assertThat(serial.getLotId()).isNull();
    }

    @Test
    void operationalMigrationAddsStatusColumnsAndTenantExpirationIndex() {
        Fixture fixture = createFixture();
        jdbc.update(
                """
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id,
                     serial_number, status, version)
                VALUES (?, ?, ?, ?, ?, 'SERIAL-IN-TRANSIT', 'IN_TRANSIT', 0)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.branchId(),
                fixture.locationId(),
                fixture.productId());

        assertThat(columnType("goods_receipt_items", "tracking_details")).isEqualTo("jsonb");
        assertThat(columnType("picking_items", "picked_traces")).isEqualTo("jsonb");
        assertThat(columnType("inventory_movements", "reference_line_id")).isEqualTo("uuid");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND indexname = 'idx_inventory_lots_tenant_expiration'
                        """,
                        String.class))
                .contains("tenant_id", "expiration_date", "expiration_date IS NOT NULL");
    }

    @Test
    void movementTraceLookupReturnsLotAndSerialBreakdownOnlyForItsTenant() {
        Fixture fixture = createFixture();
        InventoryLot lot = saveLot(fixture, "LOT-TRACE", null);
        InventorySerial serial = saveSerial(fixture, lot.getId(), "SERIAL-TRACE");
        InventoryMovementTrace lotTrace = movementTraces.saveAndFlush(InventoryMovementTrace.builder()
                .tenantId(fixture.tenantId())
                .movementId(fixture.movementId())
                .lotId(lot.getId())
                .quantity(new BigDecimal("2.000"))
                .build());
        InventoryMovementTrace serialTrace = movementTraces.saveAndFlush(InventoryMovementTrace.builder()
                .tenantId(fixture.tenantId())
                .movementId(fixture.movementId())
                .serialId(serial.getId())
                .quantity(BigDecimal.ONE)
                .build());

        assertThat(movementTraces.findByTenantIdAndMovementIdOrderByIdAsc(
                        fixture.tenantId(), fixture.movementId()))
                .extracting(InventoryMovementTrace::getId)
                .containsExactlyInAnyOrder(lotTrace.getId(), serialTrace.getId());
        assertThat(movementTraces.findByTenantIdAndMovementIdOrderByIdAsc(
                UUID.randomUUID(), fixture.movementId())).isEmpty();
    }

    @Test
    void movementTraceRequiresExactlyOnePhysicalReference() {
        Fixture fixture = createFixture();

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_movement_traces
                            (id, tenant_id, movement_id, lot_id, serial_id, quantity)
                        VALUES (?, ?, ?, NULL, NULL, 1.000)
                        """,
                        UUID.randomUUID(), fixture.tenantId(), fixture.movementId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serialMovementTraceRequiresUnitQuantity() {
        Fixture fixture = createFixture();
        InventorySerial serial = saveSerial(fixture, null, "SERIAL-QUANTITY");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_movement_traces
                            (id, tenant_id, movement_id, lot_id, serial_id, quantity)
                        VALUES (?, ?, ?, NULL, ?, 2.000)
                        """,
                        UUID.randomUUID(), fixture.tenantId(), fixture.movementId(), serial.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private InventoryLot saveLot(Fixture fixture, String lotNumber, LocalDate expirationDate) {
        InventoryLot lot = InventoryLot.builder()
                .productId(fixture.productId())
                .lotNumber(lotNumber)
                .expirationDate(expirationDate)
                .build();
        lot.setTenantId(fixture.tenantId());
        return lots.saveAndFlush(lot);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND column_name = ?
                """,
                String.class,
                table,
                column);
    }

    private InventoryLotBalance saveLotBalance(
            Fixture fixture,
            UUID lotId,
            UUID locationId,
            BigDecimal quantity,
            BigDecimal reservedQuantity) {
        InventoryLotBalance balance = InventoryLotBalance.builder()
                .branchId(fixture.branchId())
                .locationId(locationId)
                .lotId(lotId)
                .quantity(quantity)
                .reservedQuantity(reservedQuantity)
                .build();
        balance.setTenantId(fixture.tenantId());
        return lotBalances.saveAndFlush(balance);
    }

    private InventorySerial saveSerial(Fixture fixture, UUID lotId, String serialNumber) {
        return saveSerial(fixture, fixture.productId(), lotId, serialNumber);
    }

    private InventorySerial saveSerial(
            Fixture fixture, UUID productId, UUID lotId, String serialNumber) {
        InventorySerial serial = InventorySerial.builder()
                .branchId(fixture.branchId())
                .locationId(fixture.locationId())
                .productId(productId)
                .serialNumber(serialNumber)
                .lotId(lotId)
                .status(InventorySerialStatus.AVAILABLE)
                .build();
        serial.setTenantId(fixture.tenantId());
        return serials.saveAndFlush(serial);
    }

    private UUID createProduct(Fixture fixture, String label) {
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                SELECT ?, tenant_id, ?, ?, category_id, base_unit_id
                FROM products
                WHERE id = ? AND tenant_id = ?
                """,
                productId,
                "TRACE-" + label + "-" + suffix,
                "Traceability product " + label + " " + suffix,
                fixture.productId(),
                fixture.tenantId());
        return productId;
    }

    private void insertSerial(
            Fixture fixture, UUID productId, UUID lotId, String serialNumber) {
        jdbc.update(
                """
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id,
                     serial_number, lot_id, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', 0)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.branchId(),
                fixture.locationId(),
                productId,
                serialNumber,
                lotId);
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String shortSuffix = suffix.substring(0, 8);

        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Traceability tenant " + suffix, "traceability-" + suffix);
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "TR-" + shortSuffix, "Traceability branch " + suffix);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) "
                        + "VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Traceability category " + suffix, "traceability-" + suffix);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "TR-" + shortSuffix);
        jdbc.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                productId, tenantId, "TRACE-" + shortSuffix,
                "Traceability product " + suffix, categoryId, unitId);
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')",
                locationId, tenantId, branchId, "TR-" + shortSuffix, "Traceability location " + suffix);
        jdbc.update(
                """
                INSERT INTO inventory_movements
                    (id, tenant_id, branch_id, product_id, type, reason, quantity, created_at)
                VALUES (?, ?, ?, ?, 'in', 'Traceability foundation fixture', 2.000, ?)
                """,
                movementId, tenantId, branchId, productId, Timestamp.from(Instant.now()));

        return new Fixture(tenantId, branchId, productId, locationId, movementId);
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID locationId,
            UUID movementId) {}
}
