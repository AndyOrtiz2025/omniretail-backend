package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.InventoryCountResultResponse;
import com.omniretail.backend.inventory.dto.InventoryCountSnapshotResponse;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.Addition;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.LotCount;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryCountServiceTest {

    @Autowired private InventoryCountService countService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private TenantCapabilityGuard tenantCapabilityGuard;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowBranches() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void snapshotUsesOperationalLocationAndLotReconcileBecomesStaleOnRetry() {
        Fixture fixture = fixture(true, false);
        UUID lotA = insertLot(fixture, "LOT-A");
        UUID lotB = insertLot(fixture, "LOT-B");
        insertBalance(fixture, "10", "2");
        insertLotBalance(fixture, lotA, "6", "2");
        insertLotBalance(fixture, lotB, "4", "0");

        InventoryCountSnapshotResponse snapshot =
                countService.snapshot(fixture.branch(), fixture.product(), null);

        assertThat(snapshot.locationId()).isEqualTo(fixture.location());
        assertThat(snapshot.quantity()).isEqualByComparingTo("10");
        assertThat(snapshot.reservedQuantity()).isEqualByComparingTo("2");
        assertThat(snapshot.lots()).hasSize(2);

        ReconcileInventoryCountRequest request = new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), null, "Conteo de lotes", new BigDecimal("10"),
                List.of(
                        new LotCount(lotA, new BigDecimal("6"), new BigDecimal("5"), null, null),
                        new LotCount(lotB, new BigDecimal("4"), new BigDecimal("4"), null, null)),
                null, null, null);
        InventoryCountResultResponse result = countService.reconcile(request);

        assertThat(result.quantityBefore()).isEqualByComparingTo("10");
        assertThat(result.quantityAfter()).isEqualByComparingTo("9");
        assertThat(result.movementIds()).hasSize(1);
        assertThat(balance(fixture)).isEqualByComparingTo("9");
        assertThatThrownBy(() -> countService.reconcile(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COUNT_SNAPSHOT_STALE"));
        assertThat(movementCount(fixture)).isOne();
    }

    @Test
    void reservedSerialCannotBeMissingAndNoPartialMovementIsCreated() {
        Fixture fixture = fixture(false, true);
        insertBalance(fixture, "2", "1");
        insertSerial(fixture, null, "SER-1", "AVAILABLE");
        insertSerial(fixture, null, "SER-2", "RESERVED");

        ReconcileInventoryCountRequest request = new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), fixture.location(), "Conteo serial",
                new BigDecimal("2"), null, List.of("SER-1", "SER-2"), List.of("SER-1"), null);

        assertThatThrownBy(() -> countService.reconcile(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("COUNT_RESERVED_SERIAL_MISSING"));
        assertThat(balance(fixture)).isEqualByComparingTo("2");
        assertThat(movementCount(fixture)).isZero();
        assertThat(serialStatus(fixture, "SER-2")).isEqualTo("RESERVED");
    }

    @Test
    void lotAndSerialReconcileWritesOffMissingAndRegistersAdditionsAtomically() {
        Fixture fixture = fixture(true, true);
        UUID lot = insertLot(fixture, "LOT-A");
        insertBalance(fixture, "2", "0");
        insertLotBalance(fixture, lot, "2", "0");
        insertSerial(fixture, lot, "SER-1", "AVAILABLE");
        insertSerial(fixture, lot, "SER-2", "AVAILABLE");
        ReconcileInventoryCountRequest request = new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), null, "Conteo mixto", new BigDecimal("2"),
                List.of(new LotCount(
                        lot, new BigDecimal("2"), new BigDecimal("1"),
                        List.of("SER-1", "SER-2"), List.of("SER-1"))),
                null, null,
                List.of(new Addition(BigDecimal.ONE, "LOT-A", null, List.of("SER-3"))));

        InventoryCountResultResponse result = countService.reconcile(request);

        assertThat(result.quantityAfter()).isEqualByComparingTo("2");
        assertThat(result.movementIds()).hasSize(2);
        assertThat(serialStatus(fixture, "SER-2")).isEqualTo("WRITTEN_OFF");
        assertThat(serialStatus(fixture, "SER-3")).isEqualTo("AVAILABLE");
        assertThat(result.lots()).singleElement().satisfies(lotResult -> {
            assertThat(lotResult.missingSerialNumbers()).containsExactly("SER-2");
            assertThat(lotResult.addedSerialNumbers()).containsExactly("SER-3");
        });
    }

    @Test
    void failureWhileAddingRollsBackMissingSerialBalanceAndMovement() {
        Fixture fixture = fixture(false, true);
        insertBalance(fixture, "2", "0");
        insertSerial(fixture, null, "SER-1", "AVAILABLE");
        insertSerial(fixture, null, "SER-2", "AVAILABLE");
        ReconcileInventoryCountRequest request = new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), null, "Conteo fallido", new BigDecimal("2"),
                null, List.of("SER-1", "SER-2"), List.of("SER-1"),
                List.of(new Addition(BigDecimal.ONE, null, null, List.of("SER-1"))));

        assertThatThrownBy(() -> countService.reconcile(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DUPLICATE_SERIAL"));
        assertThat(balance(fixture)).isEqualByComparingTo("2");
        assertThat(serialStatus(fixture, "SER-2")).isEqualTo("AVAILABLE");
        assertThat(movementCount(fixture)).isZero();
    }

    @Test
    void explicitNonOperationalLocationAndCrossTenantProductAreRejected() {
        Fixture fixture = fixture(false, true);
        Fixture other = fixture(false, true);
        UUID secondLocation = insertLocation(fixture);
        authenticate(fixture);

        assertThatThrownBy(() -> countService.snapshot(
                        fixture.branch(), fixture.product(), secondLocation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("INVENTORY_LOCATION_MISMATCH"));
        assertThatThrownBy(() -> countService.snapshot(
                        fixture.branch(), other.product(), fixture.location()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    @Test
    void aggregateReservationMayExistBeforeLotAllocation() {
        Fixture fixture = fixture(true, false);
        UUID lot = insertLot(fixture, "LOT-PENDING");
        insertBalance(fixture, "10", "3");
        insertLotBalance(fixture, lot, "10", "0");

        InventoryCountSnapshotResponse snapshot =
                countService.snapshot(fixture.branch(), fixture.product(), null);
        InventoryCountResultResponse result = countService.reconcile(request(
                fixture, "10", List.of(lot(lot, "10", "10")), null, null, null));

        assertThat(snapshot.reservedQuantity()).isEqualByComparingTo("3");
        assertThat(snapshot.lots().getFirst().reservedQuantity()).isZero();
        assertThat(result.quantityAfter()).isEqualByComparingTo("10");
        assertThat(result.movementIds()).isEmpty();
    }

    @Test
    void aggregateReservationMayExistBeforeSerialAllocation() {
        Fixture fixture = fixture(false, true);
        insertBalance(fixture, "3", "2");
        insertSerial(fixture, null, "SER-A", "AVAILABLE");
        insertSerial(fixture, null, "SER-B", "AVAILABLE");
        insertSerial(fixture, null, "SER-C", "AVAILABLE");

        InventoryCountResultResponse result = countService.reconcile(request(
                fixture, "3", null, List.of("SER-C", "SER-A", "SER-B"),
                List.of("SER-A", "SER-B", "SER-C"), null));

        assertThat(result.quantityAfter()).isEqualByComparingTo("3");
        assertThat(result.movementIds()).isEmpty();
    }

    @Test
    void partiallyMaterializedPickingKeepsLotAndSerialReservationsConsistent() {
        Fixture fixture = fixture(true, true);
        UUID lot = insertLot(fixture, "LOT-PARTIAL");
        insertBalance(fixture, "4", "3");
        insertLotBalance(fixture, lot, "4", "1");
        insertSerial(fixture, lot, "SER-1", "RESERVED");
        insertSerial(fixture, lot, "SER-2", "AVAILABLE");
        insertSerial(fixture, lot, "SER-3", "AVAILABLE");
        insertSerial(fixture, lot, "SER-4", "AVAILABLE");

        InventoryCountResultResponse result = countService.reconcile(request(
                fixture,
                "4",
                List.of(new LotCount(
                        lot, new BigDecimal("4"), new BigDecimal("4"),
                        List.of("SER-1", "SER-2", "SER-3", "SER-4"),
                        List.of("SER-4", "SER-3", "SER-2", "SER-1"))),
                null,
                null,
                null));

        assertThat(result.quantityAfter()).isEqualByComparingTo("4");
        assertThat(result.movementIds()).isEmpty();
    }

    @Test
    void physicalReservationsAboveAggregateAreRejected() {
        Fixture lots = fixture(true, false);
        UUID lot = insertLot(lots, "LOT-OVER");
        insertBalance(lots, "2", "1");
        insertLotBalance(lots, lot, "2", "2");
        assertCode("COUNT_TRACEABILITY_INCONSISTENT", () -> countService.reconcile(request(
                lots, "2", List.of(lot(lot, "2", "2")), null, null, null)));

        Fixture serials = fixture(false, true);
        insertBalance(serials, "2", "1");
        insertSerial(serials, null, "OVER-1", "RESERVED");
        insertSerial(serials, null, "OVER-2", "RESERVED");
        assertCode("COUNT_TRACEABILITY_INCONSISTENT", () -> countService.reconcile(request(
                serials, "2", null, List.of("OVER-1", "OVER-2"),
                List.of("OVER-1", "OVER-2"), null)));
    }

    @Test
    void belowAggregateReservationAndPhysicalQuantityMismatchRemainRejected() {
        Fixture below = fixture(true, false);
        UUID belowLot = insertLot(below, "LOT-RESERVED");
        insertBalance(below, "5", "3");
        insertLotBalance(below, belowLot, "5", "0");
        assertCode("COUNT_BELOW_RESERVED", () -> countService.reconcile(request(
                below, "5", List.of(lot(belowLot, "5", "2")), null, null, null)));

        Fixture mismatch = fixture(true, false);
        UUID mismatchLot = insertLot(mismatch, "LOT-MISMATCH");
        insertBalance(mismatch, "5", "0");
        insertLotBalance(mismatch, mismatchLot, "4", "0");
        assertCode("COUNT_TRACEABILITY_INCONSISTENT", () -> countService.reconcile(request(
                mismatch, "5", List.of(lot(mismatchLot, "4", "4")), null, null, null)));
        assertThat(movementCount(below)).isZero();
        assertThat(movementCount(mismatch)).isZero();
    }

    @Test
    void lotCountRequiresCompleteSnapshotRejectsOvercountAndSharesCountId() {
        Fixture fixture = fixture(true, false);
        UUID lotA = insertLot(fixture, "LOT-A");
        UUID lotB = insertLot(fixture, "LOT-B");
        insertBalance(fixture, "6", "0");
        insertLotBalance(fixture, lotA, "3", "0");
        insertLotBalance(fixture, lotB, "3", "0");

        assertCode("COUNT_SNAPSHOT_STALE", () -> countService.reconcile(request(
                fixture, "6", List.of(lot(lotA, "3", "2")), null, null, null)));
        assertCode("COUNT_QUANTITY_EXCEEDS_REGISTERED", () -> countService.reconcile(request(
                fixture, "6", List.of(lot(lotA, "3", "4"), lot(lotB, "3", "3")),
                null, null, null)));

        InventoryCountResultResponse result = countService.reconcile(request(
                fixture, "6", List.of(lot(lotA, "3", "2"), lot(lotB, "3", "1")),
                null, null, null));
        List<Map<String, Object>> movements = movements(fixture);
        assertThat(movements).hasSize(2).allSatisfy(movement -> {
            assertThat(movement).containsEntry("reference_type", "count_correction");
            assertThat(movement).containsEntry("reference_id", result.countId());
        });
        assertThat(traceQuantity(fixture)).isEqualByComparingTo("3");
    }

    @Test
    void serialSnapshotUsesIdentityNotQuantityAndIgnoresOrder() {
        Fixture fixture = fixture(false, true);
        insertBalance(fixture, "2", "0");
        insertSerial(fixture, null, "SER-A", "AVAILABLE");
        insertSerial(fixture, null, "SER-B", "AVAILABLE");

        InventoryCountResultResponse result = countService.reconcile(request(
                fixture, "2", null, List.of("SER-B", " SER-A "), List.of("SER-A"), null));
        assertThat(result.lots().getFirst().missingSerialNumbers()).containsExactly("SER-B");

        Fixture stale = fixture(false, true);
        insertBalance(stale, "2", "0");
        insertSerial(stale, null, "OLD-A", "AVAILABLE");
        insertSerial(stale, null, "OLD-B", "AVAILABLE");
        jdbc.update("UPDATE inventory_serials SET status = 'WRITTEN_OFF' WHERE tenant_id = ? AND serial_number = 'OLD-B'",
                stale.tenant());
        insertSerial(stale, null, "NEW-B", "AVAILABLE");
        assertCode("COUNT_SNAPSHOT_STALE", () -> countService.reconcile(request(
                stale, "2", null, List.of("OLD-A", "OLD-B"), List.of("OLD-A"), null)));
        assertThat(movementCount(stale)).isZero();
    }

    @Test
    void serialMovingToAnotherLotOrLocationIsStaleAndFoundOutsideSnapshotIsRejected() {
        Fixture fixture = fixture(true, true);
        UUID lotA = insertLot(fixture, "LOT-A");
        UUID lotB = insertLot(fixture, "LOT-B");
        insertBalance(fixture, "2", "0");
        insertLotBalance(fixture, lotA, "1", "0");
        insertLotBalance(fixture, lotB, "1", "0");
        insertSerial(fixture, lotA, "SER-A", "AVAILABLE");
        insertSerial(fixture, lotB, "SER-B", "AVAILABLE");
        jdbc.update("UPDATE inventory_serials SET lot_id = ? WHERE tenant_id = ? AND serial_number = 'SER-A'",
                lotB, fixture.tenant());
        jdbc.update("UPDATE inventory_serials SET lot_id = ? WHERE tenant_id = ? AND serial_number = 'SER-B'",
                lotA, fixture.tenant());
        assertCode("COUNT_SNAPSHOT_STALE", () -> countService.reconcile(request(
                fixture,
                "2",
                List.of(
                        new LotCount(lotA, BigDecimal.ONE, BigDecimal.ONE,
                                List.of("SER-A"), List.of("SER-A")),
                        new LotCount(lotB, BigDecimal.ONE, BigDecimal.ONE,
                                List.of("SER-B"), List.of("SER-B"))),
                null, null, null)));

        Fixture moved = fixture(false, true);
        UUID secondLocation = insertLocation(moved);
        insertBalance(moved, "2", "0");
        insertSerial(moved, null, "LOC-A", "AVAILABLE");
        insertSerial(moved, null, "LOC-B", "AVAILABLE");
        insertSerialAt(moved, secondLocation, null, "LOC-C", "AVAILABLE");
        jdbc.update("UPDATE inventory_serials SET location_id = ? WHERE tenant_id = ? AND serial_number = 'LOC-B'",
                secondLocation, moved.tenant());
        jdbc.update("UPDATE inventory_serials SET location_id = ? WHERE tenant_id = ? AND serial_number = 'LOC-C'",
                moved.location(), moved.tenant());
        assertCode("COUNT_SNAPSHOT_STALE", () -> countService.reconcile(request(
                moved, "2", null, List.of("LOC-A", "LOC-B"), List.of("LOC-A"), null)));

        Fixture subset = fixture(false, true);
        insertBalance(subset, "2", "0");
        insertSerial(subset, null, "SUB-A", "AVAILABLE");
        insertSerial(subset, null, "SUB-B", "AVAILABLE");
        assertCode("SERIAL_NOT_FOUND", () -> countService.reconcile(request(
                subset, "2", null, List.of("SUB-A", "SUB-B"),
                List.of("SUB-A", "UNKNOWN"), null)));
        assertCode("COUNT_EXPECTED_SERIALS_REQUIRED", () -> countService.reconcile(request(
                subset, "2", null, null, List.of("SUB-A"), null)));
    }

    @Test
    void snapshotIncludesReservedSerialsAndEnforcesTenantBranchIsolation() {
        Fixture fixture = fixture(false, true);
        insertBalance(fixture, "2", "1");
        insertSerial(fixture, null, "FREE", "AVAILABLE");
        insertSerial(fixture, null, "HELD", "RESERVED");

        InventoryCountSnapshotResponse snapshot =
                countService.snapshot(fixture.branch(), fixture.product(), null);
        assertThat(snapshot.serials())
                .extracting(InventoryCountSnapshotResponse.Serial::serialNumber)
                .containsExactly("FREE", "HELD");

        Fixture other = fixture(false, true);
        authenticate(fixture);
        assertCode("PRODUCT_NOT_FOUND", () -> countService.snapshot(
                fixture.branch(), other.product(), fixture.location()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode("BRANCH_ACCESS_DENIED", () -> countService.snapshot(
                fixture.branch(), fixture.product(), fixture.location()));
    }

    @Test
    void additionsValidateLotAndSerialContractsAndNonTraceableProductsAreRejected() {
        Fixture lots = fixture(true, false);
        UUID lot = insertLot(lots, "LOT-A");
        insertBalance(lots, "1", "0");
        insertLotBalance(lots, lot, "1", "0");
        assertCode("LOT_NUMBER_REQUIRED", () -> countService.reconcile(request(
                lots, "1", List.of(lot(lot, "1", "1")), null, null,
                List.of(new Addition(BigDecimal.ONE, null, null, null)))));

        Fixture serials = fixture(false, true);
        insertBalance(serials, "1", "0");
        insertSerial(serials, null, "SER-1", "AVAILABLE");
        assertCode("SERIAL_COUNT_MISMATCH", () -> countService.reconcile(request(
                serials, "1", null, List.of("SER-1"), List.of("SER-1"),
                List.of(new Addition(new BigDecimal("2"), null, null, List.of("SER-NEW"))))));

        Fixture normal = fixture(false, false);
        assertCode("COUNT_PRODUCT_NOT_TRACEABLE", () -> countService.snapshot(
                normal.branch(), normal.product(), null));
    }

    private ReconcileInventoryCountRequest request(
            Fixture fixture,
            String expectedQuantity,
            List<LotCount> lots,
            List<String> expectedSerials,
            List<String> foundSerials,
            List<Addition> additions) {
        authenticate(fixture);
        return new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), null, "Conteo fisico",
                new BigDecimal(expectedQuantity), lots, expectedSerials, foundSerials, additions);
    }

    private static LotCount lot(UUID id, String expected, String counted) {
        return new LotCount(
                id, new BigDecimal(expected), new BigDecimal(counted), null, null);
    }

    private static void assertCode(String expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expected));
    }

    private Fixture fixture(boolean trackingLot, boolean trackingSerial) {
        Fixture fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Count test', ?)",
                fixture.tenant(), "count-" + fixture.tenant());
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, fixture.branch(), fixture.tenant(), "B-" + fixture.branch());
        jdbc.update("""
                INSERT INTO categories (id, tenant_id, name, slug)
                VALUES (?, ?, 'Categoria', ?)
                """, fixture.category(), fixture.tenant(), "cat-" + fixture.category());
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')
                """, fixture.unit(), fixture.tenant(), "U-" + fixture.unit().toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, category_id, base_unit_id, product_type,
                     tracking_stock, tracking_lot, tracking_serial)
                VALUES (?, ?, ?, 'Producto conteo', ?, ?, 'physical', true, ?, ?)
                """, fixture.product(), fixture.tenant(), "SKU-" + fixture.product(),
                fixture.category(), fixture.unit(), trackingLot, trackingSerial);
        insertLocation(fixture);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Contador', ?, 'employee', 'active', ?)
                """, fixture.user(), fixture.tenant(), fixture.user() + "@test.local", fixture.branch());
        jdbc.update("""
                INSERT INTO business_capabilities_configs
                    (id, tenant_id, preset, supports_multiple_locations)
                VALUES (?, ?, 'custom', true)
                """, UUID.randomUUID(), fixture.tenant());
        jdbc.update("""
                INSERT INTO product_inventory_settings
                    (tenant_id, branch_id, product_id, default_location_id)
                VALUES (?, ?, ?, ?)
                """, fixture.tenant(), fixture.branch(), fixture.product(), fixture.location());
        authenticate(fixture);
        return fixture;
    }

    private void authenticate(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee, null,
                fixture.branch(), UUID.randomUUID()));
    }

    private UUID insertLocation(Fixture fixture) {
        UUID id = fixture.location();
        if (jdbc.queryForObject(
                "SELECT count(*) FROM locations WHERE id = ?", Long.class, id) > 0) {
            id = UUID.randomUUID();
        }
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')
                """, id, fixture.tenant(), fixture.branch(), "L-" + id);
        return id;
    }

    private UUID insertLot(Fixture fixture, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, ?)
                """, id, fixture.tenant(), fixture.product(), number);
        return id;
    }

    private void insertBalance(Fixture fixture, String quantity, String reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)
                """, UUID.randomUUID(), fixture.tenant(), fixture.branch(), fixture.product(),
                fixture.location(), quantity, reserved);
    }

    private void insertLotBalance(
            Fixture fixture, UUID lot, String quantity, String reserved) {
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)
                """, UUID.randomUUID(), fixture.tenant(), fixture.branch(), fixture.location(),
                lot, quantity, reserved);
    }

    private void insertSerial(Fixture fixture, UUID lot, String number, String status) {
        insertSerialAt(fixture, fixture.location(), lot, number, status);
    }

    private void insertSerialAt(
            Fixture fixture, UUID location, UUID lot, String number, String status) {
        jdbc.update("""
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number, lot_id, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), fixture.tenant(), fixture.branch(), location,
                fixture.product(), number, lot, status);
    }

    private BigDecimal balance(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND product_id = ?",
                BigDecimal.class, fixture.tenant(), fixture.product());
    }

    private String serialStatus(Fixture fixture, String serial) {
        return jdbc.queryForObject("""
                SELECT status FROM inventory_serials
                WHERE tenant_id = ? AND product_id = ? AND serial_number = ?
                """, String.class, fixture.tenant(), fixture.product(), serial);
    }

    private long movementCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?",
                Long.class, fixture.tenant());
    }

    private List<Map<String, Object>> movements(Fixture fixture) {
        return jdbc.queryForList("""
                SELECT reference_type, reference_id, quantity
                FROM inventory_movements
                WHERE tenant_id = ? AND product_id = ?
                ORDER BY created_at, id
                """, fixture.tenant(), fixture.product());
    }

    private BigDecimal traceQuantity(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(trace.quantity), 0)
                FROM inventory_movement_traces trace
                JOIN inventory_movements movement ON movement.id = trace.movement_id
                WHERE trace.tenant_id = ? AND movement.product_id = ?
                """, BigDecimal.class, fixture.tenant(), fixture.product());
    }

    private record Fixture(
            UUID tenant,
            UUID branch,
            UUID category,
            UUID unit,
            UUID product,
            UUID location,
            UUID user) {}
}
