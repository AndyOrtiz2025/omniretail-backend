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
import com.omniretail.backend.inventory.dto.InventoryCountResultResponse.LotResult;
import com.omniretail.backend.inventory.dto.InventoryCountSnapshotResponse;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.Addition;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.LotCount;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Autowired private InventoryCountService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private TenantCapabilityGuard tenantCapabilityGuard;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowAllBranches() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    // ------------------------------------------------------------- snapshot

    @Test
    void snapshotShowsPhysicalLotsIncludingReservedStock() {
        Fixture fixture = fixture(true, true, false);
        authenticate(fixture);
        LocalDate expiration = LocalDate.now().plusDays(30);
        UUID lotA = insertLot(fixture, "LOT-A", expiration);
        UUID lotB = insertLot(fixture, "LOT-B", expiration.plusDays(5));
        insertBalance(fixture, fixture.location(), "15", "4");
        insertLotBalance(fixture, lotA, fixture.location(), "10", "4");
        insertLotBalance(fixture, lotB, fixture.location(), "5", "0");

        InventoryCountSnapshotResponse snapshot =
                service.snapshot(fixture.branch(), fixture.product(), fixture.location());

        assertThat(snapshot.quantity()).isEqualByComparingTo("15");
        assertThat(snapshot.reservedQuantity()).isEqualByComparingTo("4");
        assertThat(snapshot.availableQuantity()).isEqualByComparingTo("11");
        assertThat(snapshot.tracking().lot()).isTrue();
        assertThat(snapshot.tracking().expiration()).isTrue();
        assertThat(snapshot.tracking().serial()).isFalse();
        assertThat(snapshot.lots()).extracting(InventoryCountSnapshotResponse.Lot::lotNumber)
                .containsExactly("LOT-A", "LOT-B");
        assertThat(snapshot.lots().getFirst().quantity()).isEqualByComparingTo("10");
        assertThat(snapshot.lots().getFirst().reservedQuantity()).isEqualByComparingTo("4");
        assertThat(snapshot.lots().getFirst().expirationDate()).isEqualTo(expiration);
        assertThat(snapshot.sku()).startsWith("SKU-");
        assertThat(snapshot.locationName()).startsWith("Ubicacion");
    }

    @Test
    void snapshotListsOnlyPhysicallyPresentSerialsIncludingReserved() {
        Fixture fixture = fixture(false, false, true);
        authenticate(fixture);
        insertBalance(fixture, fixture.location(), "2", "1");
        insertSerial(fixture, null, fixture.location(), "S-AVAILABLE", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "S-RESERVED", "RESERVED");
        insertSerial(fixture, null, fixture.location(), "S-WRITTEN-OFF", "WRITTEN_OFF");
        insertSerial(fixture, null, fixture.location(), "S-CONSUMED", "CONSUMED");
        insertSerial(fixture, null, fixture.location(), "S-IN-TRANSIT", "IN_TRANSIT");
        insertSerial(fixture, null, fixture.secondLocation(), "S-OTHER-LOCATION", "AVAILABLE");

        InventoryCountSnapshotResponse snapshot =
                service.snapshot(fixture.branch(), fixture.product(), fixture.location());

        assertThat(snapshot.serials())
                .extracting(InventoryCountSnapshotResponse.Serial::serialNumber)
                .containsExactly("S-AVAILABLE", "S-RESERVED");
        assertThat(snapshot.lots()).isEmpty();
    }

    @Test
    void snapshotIsIsolatedByTenantAndBranchAccess() {
        Fixture fixture = fixture(true, false, false);
        Fixture other = fixture(true, false, false);
        authenticate(fixture);

        assertCode("PRODUCT_NOT_FOUND", () -> service.snapshot(fixture.branch(), other.product(), null));
        assertCode("BRANCH_NOT_FOUND", () -> service.snapshot(other.branch(), fixture.product(), null));

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode("BRANCH_ACCESS_DENIED", () -> service.snapshot(fixture.branch(), fixture.product(), null));
    }

    // ------------------------------------------------------------- lot only

    @Test
    void lotOnlyCountDecrementsSeveralLotsInOneTransactionWithSharedCountId() {
        Fixture fixture = fixture(true, false, false);
        UUID lotA = insertLot(fixture, "LOT-A", null);
        UUID lotB = insertLot(fixture, "LOT-B", null);
        UUID lotC = insertLot(fixture, "LOT-C", null);
        insertBalance(fixture, fixture.location(), "40", "0");
        insertLotBalance(fixture, lotA, fixture.location(), "20", "0");
        insertLotBalance(fixture, lotB, fixture.location(), "10", "0");
        insertLotBalance(fixture, lotC, fixture.location(), "10", "0");

        InventoryCountResultResponse result = service.reconcile(request(
                fixture, "40",
                List.of(lot(lotA, "20", "12"), lot(lotB, "10", "10"), lot(lotC, "10", "5")),
                null, null));

        assertThat(result.quantityBefore()).isEqualByComparingTo("40");
        assertThat(result.countedQuantity()).isEqualByComparingTo("27");
        assertThat(result.quantityAfter()).isEqualByComparingTo("27");
        assertThat(result.delta()).isEqualByComparingTo("-13");
        assertThat(result.performedByName()).startsWith("Usuario");
        assertThat(result.branchName()).isNotBlank();
        assertThat(result.lots()).extracting(LotResult::lotNumber).containsExactly("LOT-A", "LOT-B", "LOT-C");
        assertThat(result.lots()).extracting(lotResult -> lotResult.delta().intValue()).containsExactly(-8, 0, -5);
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("27");
        assertThat(lotBalance(fixture, lotA)).isEqualByComparingTo("12");
        assertThat(lotBalance(fixture, lotB)).isEqualByComparingTo("10");
        assertThat(lotBalance(fixture, lotC)).isEqualByComparingTo("5");

        // Un movimiento por lote con diferencia, todos con el mismo countId y count_correction.
        List<Map<String, Object>> movements = movements(fixture);
        assertThat(movements).hasSize(2).allSatisfy(movement -> {
            assertThat(movement).containsEntry("type", "out");
            assertThat(movement).containsEntry("reference_type", "count_correction");
            assertThat(movement).containsEntry("reference_id", result.countId());
        });
        assertThat(result.movementIds()).hasSize(2);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(quantity) FROM inventory_movement_traces WHERE tenant_id = ? AND lot_id IS NOT NULL AND serial_id IS NULL",
                        BigDecimal.class,
                        fixture.tenant()))
                .isEqualByComparingTo("13");
    }

    @Test
    void lotOnlyCountRejectsStaleSnapshotOmittedLotAndOvercount() {
        Fixture fixture = fixture(true, false, false);
        UUID lotA = insertLot(fixture, "LOT-A", null);
        UUID lotB = insertLot(fixture, "LOT-B", null);
        insertBalance(fixture, fixture.location(), "30", "0");
        insertLotBalance(fixture, lotA, fixture.location(), "20", "0");
        insertLotBalance(fixture, lotB, fixture.location(), "10", "0");

        // Otra operación cambió el lote A después de abrir el conteo.
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(request(
                fixture, "30", List.of(lot(lotA, "18", "10"), lot(lotB, "10", "10")), null, null)));
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(request(
                fixture, "29", List.of(lot(lotA, "20", "10"), lot(lotB, "10", "10")), null, null)));
        // Un lote existente que no vino en el conteo no se asume como faltante.
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(request(
                fixture, "30", List.of(lot(lotA, "20", "10")), null, null)));
        assertCode("COUNT_QUANTITY_EXCEEDS_REGISTERED", () -> service.reconcile(request(
                fixture, "30", List.of(lot(lotA, "20", "21"), lot(lotB, "10", "10")), null, null)));

        assertThat(movements(fixture)).isEmpty();
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("30");
        assertThat(lotBalance(fixture, lotA)).isEqualByComparingTo("20");
    }

    @Test
    void countThatWouldLeaveLessThanReservedIsRejectedWithoutPartialChanges() {
        Fixture fixture = fixture(true, false, false);
        UUID lotA = insertLot(fixture, "LOT-A", null);
        UUID lotB = insertLot(fixture, "LOT-B", null);
        insertBalance(fixture, fixture.location(), "30", "6");
        insertLotBalance(fixture, lotA, fixture.location(), "20", "0");
        insertLotBalance(fixture, lotB, fixture.location(), "10", "6");

        assertCode("COUNT_BELOW_RESERVED", () -> service.reconcile(request(
                fixture, "30", List.of(lot(lotA, "20", "5"), lot(lotB, "10", "4")), null, null)));

        assertThat(movements(fixture)).isEmpty();
        assertThat(lotBalance(fixture, lotA)).isEqualByComparingTo("20");
        assertThat(lotBalance(fixture, lotB)).isEqualByComparingTo("10");
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("30");
    }

    @Test
    void failingAdditionRollsBackDecrementsAlreadyApplied() {
        Fixture fixture = fixture(true, true, false);
        LocalDate expiration = LocalDate.now().plusDays(30);
        UUID lotA = insertLot(fixture, "LOT-A", expiration);
        insertBalance(fixture, fixture.location(), "10", "0");
        insertLotBalance(fixture, lotA, fixture.location(), "10", "0");

        // La unidad extra reutiliza LOT-A con otro vencimiento: falla después de aplicar la merma.
        assertCode("LOT_EXPIRATION_MISMATCH", () -> service.reconcile(request(
                fixture, "10", List.of(lot(lotA, "10", "6")), null,
                List.of(new Addition(new BigDecimal("2"), "LOT-A", expiration.plusDays(9), null)))));

        assertThat(movements(fixture)).isEmpty();
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("10");
        assertThat(lotBalance(fixture, lotA)).isEqualByComparingTo("10");
    }

    @Test
    void lotAdditionsRequireLotAndExpirationAndCreateInMovementsWithSameCountId() {
        Fixture fixture = fixture(true, true, false);
        LocalDate expiration = LocalDate.now().plusDays(30);
        UUID lotA = insertLot(fixture, "LOT-A", expiration);
        insertBalance(fixture, fixture.location(), "10", "0");
        insertLotBalance(fixture, lotA, fixture.location(), "10", "0");
        List<LotCount> lots = List.of(lot(lotA, "10", "10"));

        assertCode("LOT_NUMBER_REQUIRED", () -> service.reconcile(request(
                fixture, "10", lots, null, List.of(new Addition(new BigDecimal("3"), null, expiration, null)))));
        assertCode("LOT_EXPIRATION_REQUIRED", () -> service.reconcile(request(
                fixture, "10", lots, null, List.of(new Addition(new BigDecimal("3"), "LOT-NEW", null, null)))));

        InventoryCountResultResponse result = service.reconcile(request(
                fixture, "10", lots, null,
                List.of(new Addition(new BigDecimal("3"), "LOT-NEW", expiration.plusDays(1), null))));

        assertThat(result.quantityBefore()).isEqualByComparingTo("10");
        assertThat(result.countedQuantity()).isEqualByComparingTo("13");
        assertThat(result.quantityAfter()).isEqualByComparingTo("13");
        assertThat(result.lots()).extracting(LotResult::lotNumber).containsExactly("LOT-A", "LOT-NEW");
        assertThat(result.lots().get(1).quantityBefore()).isEqualByComparingTo("0");
        assertThat(movements(fixture)).singleElement().satisfies(movement -> {
            assertThat(movement).containsEntry("type", "in");
            assertThat(movement).containsEntry("reference_type", "count_correction");
            assertThat(movement).containsEntry("reference_id", result.countId());
        });
        assertThat(lotBalance(fixture, lotId(fixture, "LOT-NEW"))).isEqualByComparingTo("3");
    }

    // ---------------------------------------------------------- serial only

    @Test
    void serialCountKeepsFoundSerialsAndWritesOffTheMissingOnes() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "3", "0");
        insertSerial(fixture, null, fixture.location(), "SER-001", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-002", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-003", "AVAILABLE");

        InventoryCountResultResponse result = service.reconcile(
                request(fixture, "3", null, List.of("SER-001", "SER-003"), null));

        assertThat(serialStatus(fixture, "SER-001")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "SER-002")).isEqualTo("WRITTEN_OFF");
        assertThat(serialStatus(fixture, "SER-003")).isEqualTo("AVAILABLE");
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("2");
        assertThat(result.delta()).isEqualByComparingTo("-1");
        assertThat(result.lots()).singleElement().satisfies(lotResult -> {
            assertThat(lotResult.lotId()).isNull();
            assertThat(lotResult.foundSerialNumbers()).containsExactly("SER-001", "SER-003");
            assertThat(lotResult.missingSerialNumbers()).containsExactly("SER-002");
        });
        assertThat(movements(fixture)).singleElement().satisfies(movement -> {
            assertThat(movement).containsEntry("reference_type", "count_correction");
            assertThat(movement).containsEntry("reference_id", result.countId());
        });
        assertThat(jdbc.queryForList(
                        "SELECT s.serial_number FROM inventory_movement_traces t JOIN inventory_serials s ON s.id = t.serial_id WHERE t.tenant_id = ?",
                        String.class,
                        fixture.tenant()))
                .containsExactly("SER-002");
    }

    @Test
    void reservedSerialsStayPresentAndCannotBeDeclaredMissing() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "2", "1");
        insertSerial(fixture, null, fixture.location(), "SER-FREE", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-HELD", "RESERVED");

        assertCode("COUNT_RESERVED_SERIAL_MISSING", () -> service.reconcile(
                request(fixture, "2", null, List.of("SER-FREE"), null)));
        assertThat(serialStatus(fixture, "SER-HELD")).isEqualTo("RESERVED");
        assertThat(movements(fixture)).isEmpty();

        service.reconcile(request(fixture, "2", null, List.of("SER-HELD"), null));
        assertThat(serialStatus(fixture, "SER-HELD")).isEqualTo("RESERVED");
        assertThat(serialStatus(fixture, "SER-FREE")).isEqualTo("WRITTEN_OFF");
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("1");
    }

    @Test
    void foundSerialsFromAnotherProductBranchOrLocationAreRejected() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "1", "0");
        insertSerial(fixture, null, fixture.location(), "SER-OWN", "AVAILABLE");
        UUID otherProduct = insertProduct(fixture, false, false, true);
        insertSerialFor(fixture, otherProduct, null, fixture.location(), fixture.branch(), "SER-OTHER-PRODUCT", "AVAILABLE");
        insertSerial(fixture, null, fixture.secondLocation(), "SER-OTHER-LOCATION", "AVAILABLE");
        insertSerialFor(
                fixture, fixture.product(), null, fixture.otherBranchLocation(), fixture.secondBranch(),
                "SER-OTHER-BRANCH", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-GONE", "WRITTEN_OFF");

        assertCode("SERIAL_NOT_FOUND", () -> service.reconcile(
                request(fixture, "1", null, List.of("SER-OWN", "SER-OTHER-PRODUCT"), null)));
        assertCode("SERIAL_LOCATION_MISMATCH", () -> service.reconcile(
                request(fixture, "1", null, List.of("SER-OWN", "SER-OTHER-LOCATION"), null)));
        assertCode("SERIAL_LOCATION_MISMATCH", () -> service.reconcile(
                request(fixture, "1", null, List.of("SER-OWN", "SER-OTHER-BRANCH"), null)));
        assertCode("SERIAL_NOT_PRESENT", () -> service.reconcile(
                request(fixture, "1", null, List.of("SER-OWN", "SER-GONE"), null)));
        assertThat(serialStatus(fixture, "SER-OWN")).isEqualTo("AVAILABLE");
        assertThat(movements(fixture)).isEmpty();
    }

    @Test
    void serialAdditionsValidateCountsAndDuplicatesAndCreateAvailableSerials() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "2", "0");
        insertSerial(fixture, null, fixture.location(), "SER-001", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-002", "AVAILABLE");
        List<String> found = List.of("SER-001", "SER-002");

        assertCode("SERIAL_COUNT_MISMATCH", () -> service.reconcile(request(
                fixture, "2", null, found,
                List.of(new Addition(new BigDecimal("2"), null, null, List.of("SER-NEW-1"))))));
        assertCode("DUPLICATE_SERIAL", () -> service.reconcile(request(
                fixture, "2", null, found,
                List.of(new Addition(new BigDecimal("1"), null, null, List.of("SER-001"))))));
        assertCode("DUPLICATE_SERIAL", () -> service.reconcile(request(
                fixture, "2", null, found,
                List.of(
                        new Addition(new BigDecimal("1"), null, null, List.of("SER-NEW-1")),
                        new Addition(new BigDecimal("1"), null, null, List.of("SER-NEW-1"))))));
        assertThat(movements(fixture)).isEmpty();

        InventoryCountResultResponse result = service.reconcile(request(
                fixture, "2", null, found,
                List.of(new Addition(new BigDecimal("1"), null, null, List.of("SER-NEW-1")))));

        assertThat(serialStatus(fixture, "SER-NEW-1")).isEqualTo("AVAILABLE");
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("3");
        assertThat(result.countedQuantity()).isEqualByComparingTo("3");
        assertThat(result.lots()).singleElement().satisfies(lotResult ->
                assertThat(lotResult.addedSerialNumbers()).containsExactly("SER-NEW-1"));
        assertThat(movements(fixture)).singleElement().satisfies(movement -> {
            assertThat(movement).containsEntry("type", "in");
            assertThat(movement).containsEntry("reference_type", "count_correction");
            assertThat(movement).containsEntry("reference_id", result.countId());
        });
    }

    // ------------------------------------------------------- lot + serial

    @Test
    void lotAndSerialCountGroupsFoundSerialsByLotAndKeepsOtherLotsIntact() {
        Fixture fixture = fixture(true, false, true);
        UUID lotA = insertLot(fixture, "LOT-A", null);
        UUID lotB = insertLot(fixture, "LOT-B", null);
        insertBalance(fixture, fixture.location(), "5", "0");
        insertLotBalance(fixture, lotA, fixture.location(), "3", "0");
        insertLotBalance(fixture, lotB, fixture.location(), "2", "0");
        insertSerial(fixture, lotA, fixture.location(), "A-1", "AVAILABLE");
        insertSerial(fixture, lotA, fixture.location(), "A-2", "AVAILABLE");
        insertSerial(fixture, lotA, fixture.location(), "A-3", "AVAILABLE");
        insertSerial(fixture, lotB, fixture.location(), "B-1", "AVAILABLE");
        insertSerial(fixture, lotB, fixture.location(), "B-2", "AVAILABLE");

        // Un serial del lote B declarado dentro del lote A no es válido.
        assertCode("SERIAL_LOT_MISMATCH", () -> service.reconcile(request(
                fixture, "5",
                List.of(
                        lotSerials(fixture, lotA, "3", List.of("A-1", "B-1")),
                        lotSerials(fixture, lotB, "2", List.of("B-2"))),
                null, null)));
        assertThat(movements(fixture)).isEmpty();

        InventoryCountResultResponse result = service.reconcile(request(
                fixture, "5",
                List.of(
                        lotSerials(fixture, lotA, "3", List.of("A-1")),
                        lotSerials(fixture, lotB, "2", List.of("B-1", "B-2"))),
                null, null));

        assertThat(serialStatus(fixture, "A-1")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "A-2")).isEqualTo("WRITTEN_OFF");
        assertThat(serialStatus(fixture, "A-3")).isEqualTo("WRITTEN_OFF");
        assertThat(serialStatus(fixture, "B-1")).isEqualTo("AVAILABLE");
        assertThat(lotBalance(fixture, lotA)).isEqualByComparingTo("1");
        assertThat(lotBalance(fixture, lotB)).isEqualByComparingTo("2");
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("3");
        assertThat(result.lots().getFirst().missingSerialNumbers()).containsExactly("A-2", "A-3");
        assertThat(movements(fixture)).singleElement().satisfies(movement -> {
            assertThat(movement).containsEntry("reference_type", "count_correction");
            assertThat(movement).containsEntry("reference_id", result.countId());
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ? AND serial_id IS NOT NULL AND lot_id IS NULL",
                        Long.class,
                        fixture.tenant()))
                .isEqualTo(2L);
    }

    // ---------------------------------------- composición serial esperada

    @Test
    void expectedSerialSetIsComparedWithoutDependingOnOrder() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "3", "0");
        insertSerial(fixture, null, fixture.location(), "SER-1", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-2", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-3", "AVAILABLE");

        InventoryCountResultResponse result = service.reconcile(explicitSerialRequest(
                fixture, "3", List.of("SER-3", " SER-1 ", "SER-2"), List.of("SER-1", "SER-3")));

        assertThat(result.lots().getFirst().missingSerialNumbers()).containsExactly("SER-2");
        assertThat(serialStatus(fixture, "SER-2")).isEqualTo("WRITTEN_OFF");
        assertThat(serialStatus(fixture, "SER-1")).isEqualTo("AVAILABLE");
    }

    @Test
    void sameQuantityWithADifferentSerialCompositionIsStaleAndNothingChanges() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "2", "0");
        insertSerial(fixture, null, fixture.location(), "SER-A", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-B", "AVAILABLE");
        ReconcileInventoryCountRequest snapshotView =
                explicitSerialRequest(fixture, "2", List.of("SER-A", "SER-B"), List.of("SER-A"));

        // B sale y Z entra: la cantidad sigue en 2 pero la identidad cambió.
        jdbc.update(
                "UPDATE inventory_serials SET status = 'WRITTEN_OFF' WHERE tenant_id = ? AND serial_number = 'SER-B'",
                fixture.tenant());
        insertSerial(fixture, null, fixture.location(), "SER-Z", "AVAILABLE");

        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(snapshotView));

        assertThat(serialStatus(fixture, "SER-Z")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "SER-A")).isEqualTo("AVAILABLE");
        assertThat(balance(fixture, fixture.location())).isEqualByComparingTo("2");
        assertThat(movements(fixture)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_movement_traces WHERE tenant_id = ?",
                        Long.class,
                        fixture.tenant()))
                .isZero();
    }

    @Test
    void anyExtraMissingOrSwappedSerialMakesTheSnapshotStale() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "3", "0");
        insertSerial(fixture, null, fixture.location(), "SER-A", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-B", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-C", "AVAILABLE");
        List<String> snapshot = List.of("SER-A", "SER-B", "SER-C");

        // Un serial extra (actual ⊃ esperado).
        insertSerial(fixture, null, fixture.location(), "SER-D", "AVAILABLE");
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(
                explicitSerialRequest(fixture, "3", snapshot, List.of("SER-A"))));
        // Uno faltante con la cantidad intacta en el balance (actual ⊂ esperado).
        jdbc.update("DELETE FROM inventory_serials WHERE tenant_id = ? AND serial_number = 'SER-D'", fixture.tenant());
        jdbc.update(
                "UPDATE inventory_serials SET status = 'CONSUMED' WHERE tenant_id = ? AND serial_number = 'SER-C'",
                fixture.tenant());
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(
                explicitSerialRequest(fixture, "3", snapshot, List.of("SER-A"))));
        // Uno cambiado por otro (mismo tamaño, distinta identidad).
        insertSerial(fixture, null, fixture.location(), "SER-E", "AVAILABLE");
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(
                explicitSerialRequest(fixture, "3", snapshot, List.of("SER-A"))));

        assertThat(serialStatus(fixture, "SER-A")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "SER-B")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "SER-E")).isEqualTo("AVAILABLE");
        assertThat(movements(fixture)).isEmpty();
    }

    @Test
    void serialMovingToAnotherLotOrLocationIsStaleEvenWithUnchangedQuantities() {
        Fixture fixture = fixture(true, false, true);
        UUID lotA = insertLot(fixture, "LOT-A", null);
        UUID lotB = insertLot(fixture, "LOT-B", null);
        insertBalance(fixture, fixture.location(), "3", "0");
        insertLotBalance(fixture, lotA, fixture.location(), "2", "0");
        insertLotBalance(fixture, lotB, fixture.location(), "1", "0");
        insertSerial(fixture, lotA, fixture.location(), "A-1", "AVAILABLE");
        insertSerial(fixture, lotA, fixture.location(), "A-2", "AVAILABLE");
        insertSerial(fixture, lotB, fixture.location(), "B-1", "AVAILABLE");
        List<LotCount> snapshot = List.of(
                lotSerials(fixture, lotA, "2", List.of("A-1", "A-2")),
                lotSerials(fixture, lotB, "1", List.of("B-1")));

        // A-2 pasa al lote B; los balances de lote y el total no cambian.
        jdbc.update(
                "UPDATE inventory_serials SET lot_id = ? WHERE tenant_id = ? AND serial_number = 'A-2'",
                lotB, fixture.tenant());
        authenticate(fixture);
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), fixture.location(), "Conteo fisico",
                new BigDecimal("3"), snapshot, null, null, null)));

        // Y a otra ubicación: la composición física de la ubicación contada ya no es la misma.
        jdbc.update(
                "UPDATE inventory_serials SET lot_id = ? WHERE tenant_id = ? AND serial_number = 'A-2'",
                lotA, fixture.tenant());
        jdbc.update(
                "UPDATE inventory_serials SET location_id = ? WHERE tenant_id = ? AND serial_number = 'A-1'",
                fixture.secondLocation(), fixture.tenant());
        assertCode("COUNT_SNAPSHOT_STALE", () -> service.reconcile(new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), fixture.location(), "Conteo fisico",
                new BigDecimal("3"), snapshot, null, null, null)));

        assertThat(movements(fixture)).isEmpty();
        assertThat(lotBalance(fixture, lotA)).isEqualByComparingTo("2");
        assertThat(lotBalance(fixture, lotB)).isEqualByComparingTo("1");
        assertThat(serialStatus(fixture, "A-2")).isEqualTo("AVAILABLE");
    }

    @Test
    void foundSerialsMustBeASubsetOfTheExpectedSnapshotAndExpectedIsRequired() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "2", "0");
        insertSerial(fixture, null, fixture.location(), "SER-A", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-B", "AVAILABLE");
        List<String> snapshot = List.of("SER-A", "SER-B");

        assertCode("SERIAL_NOT_FOUND", () -> service.reconcile(
                explicitSerialRequest(fixture, "2", snapshot, List.of("SER-A", "SER-Z"))));
        assertCode("COUNT_EXPECTED_SERIALS_REQUIRED", () -> service.reconcile(
                explicitSerialRequest(fixture, "2", null, List.of("SER-A"))));
        assertCode("DUPLICATE_SERIAL", () -> service.reconcile(
                explicitSerialRequest(fixture, "2", List.of("SER-A", "SER-A"), List.of("SER-A"))));
        assertCode("DUPLICATE_SERIAL", () -> service.reconcile(
                explicitSerialRequest(fixture, "2", snapshot, List.of("SER-A", "SER-A"))));

        assertThat(serialStatus(fixture, "SER-B")).isEqualTo("AVAILABLE");
        assertThat(movements(fixture)).isEmpty();
    }

    @Test
    void reservedSerialInTheSnapshotStillCannotBeMissingAfterTheCompositionMatches() {
        Fixture fixture = fixture(false, false, true);
        insertBalance(fixture, fixture.location(), "2", "1");
        insertSerial(fixture, null, fixture.location(), "SER-FREE", "AVAILABLE");
        insertSerial(fixture, null, fixture.location(), "SER-HELD", "RESERVED");

        assertCode("COUNT_RESERVED_SERIAL_MISSING", () -> service.reconcile(explicitSerialRequest(
                fixture, "2", List.of("SER-HELD", "SER-FREE"), List.of("SER-FREE"))));
        assertThat(serialStatus(fixture, "SER-HELD")).isEqualTo("RESERVED");
        assertThat(movements(fixture)).isEmpty();
    }

    @Test
    void nonTraceableProductsUseTheNormalAdjustmentFlow() {
        Fixture fixture = fixture(false, false, false);
        authenticate(fixture);

        assertCode("COUNT_PRODUCT_NOT_TRACEABLE", () -> service.reconcile(
                request(fixture, "0", null, null, null)));
    }

    // -------------------------------------------------------------- helpers

    private ReconcileInventoryCountRequest request(
            Fixture fixture,
            String expectedQuantity,
            List<LotCount> lots,
            List<String> foundSerials,
            List<Addition> additions) {
        authenticate(fixture);
        // Lo que el frontend sacaría del snapshot: los seriales físicos sin lote en el momento de abrirlo.
        List<String> expectedSerials = foundSerials == null ? null : physicalSerials(fixture, null);
        return new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), fixture.location(), "Conteo fisico",
                new BigDecimal(expectedQuantity), lots, expectedSerials, foundSerials, additions);
    }

    private ReconcileInventoryCountRequest explicitSerialRequest(
            Fixture fixture, String expectedQuantity, List<String> expectedSerials, List<String> foundSerials) {
        authenticate(fixture);
        return new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), fixture.location(), "Conteo fisico",
                new BigDecimal(expectedQuantity), null, expectedSerials, foundSerials, null);
    }

    private static LotCount lot(UUID lotId, String expected, String counted) {
        return new LotCount(lotId, new BigDecimal(expected), new BigDecimal(counted), null, null);
    }

    private LotCount lotSerials(Fixture fixture, UUID lotId, String expected, List<String> found) {
        return new LotCount(
                lotId, new BigDecimal(expected), null, physicalSerials(fixture, lotId), found);
    }

    private List<String> physicalSerials(Fixture fixture, UUID lotId) {
        return jdbc.queryForList(
                "SELECT serial_number FROM inventory_serials WHERE tenant_id = ? AND product_id = ? AND location_id = ? AND status IN ('AVAILABLE', 'RESERVED') AND lot_id IS NOT DISTINCT FROM ? ORDER BY serial_number",
                String.class, fixture.tenant(), fixture.product(), fixture.location(), lotId);
    }

    private void authenticate(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee, null, fixture.branch(), UUID.randomUUID()));
    }

    private Fixture fixture(boolean trackingLot, boolean trackingExpiration, boolean trackingSerial) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID secondBranch = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID secondLocation = UUID.randomUUID();
        UUID otherBranchLocation = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenant, "Tenant " + suffix, "tenant-" + suffix);
        insertBranch(tenant, branch, "MAIN-" + suffix);
        insertBranch(tenant, secondBranch, "SECOND-" + suffix);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                category, tenant, "Categoria " + suffix, "categoria-" + suffix);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unit, tenant, "U-" + suffix.substring(0, 8));
        insertLocation(tenant, branch, location, "LOC-A-" + suffix);
        insertLocation(tenant, branch, secondLocation, "LOC-B-" + suffix);
        insertLocation(tenant, secondBranch, otherBranchLocation, "LOC-C-" + suffix);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) VALUES (?, ?, ?, ?, 'employee', 'active', ?)",
                user, tenant, "Usuario " + suffix, "user-" + suffix + "@test.local", branch);
        Fixture fixture = new Fixture(
                tenant, branch, secondBranch, category, unit, product, user, location, secondLocation,
                otherBranchLocation);
        jdbc.update(
                """
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                VALUES (?, ?, ?, ?, 'physical', ?, ?, true, ?, ?, ?)
                """,
                product, tenant, "SKU-" + suffix, "Producto " + suffix, category, unit,
                trackingLot, trackingExpiration, trackingSerial);
        return fixture;
    }

    private UUID insertProduct(Fixture fixture, boolean lot, boolean expiration, boolean serial) {
        UUID product = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                VALUES (?, ?, ?, 'Otro producto', 'physical', ?, ?, true, ?, ?, ?)
                """,
                product, fixture.tenant(), "SKU-" + product, fixture.category(), fixture.unit(),
                lot, expiration, serial);
        return product;
    }

    private void insertBranch(UUID tenant, UUID branch, String code) {
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'main', 'active')",
                branch, tenant, code, "Sucursal " + code);
    }

    private void insertLocation(UUID tenant, UUID branch, UUID location, String code) {
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')",
                location, tenant, branch, code, "Ubicacion " + code);
    }

    private UUID insertLot(Fixture fixture, String lotNumber, LocalDate expiration) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number, expiration_date) VALUES (?, ?, ?, ?, ?)",
                id, fixture.tenant(), fixture.product(), lotNumber, expiration);
        return id;
    }

    private void insertBalance(Fixture fixture, UUID location, String quantity, String reserved) {
        jdbc.update(
                "INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), fixture.tenant(), fixture.branch(), fixture.product(), location, quantity,
                reserved);
    }

    private void insertLotBalance(Fixture fixture, UUID lot, UUID location, String quantity, String reserved) {
        jdbc.update(
                "INSERT INTO inventory_lot_balances (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), fixture.tenant(), fixture.branch(), location, lot, quantity, reserved);
    }

    private void insertSerial(Fixture fixture, UUID lot, UUID location, String number, String status) {
        insertSerialFor(fixture, fixture.product(), lot, location, fixture.branch(), number, status);
    }

    private void insertSerialFor(
            Fixture fixture, UUID product, UUID lot, UUID location, UUID branch, String number, String status) {
        jdbc.update(
                "INSERT INTO inventory_serials (id, tenant_id, branch_id, location_id, product_id, serial_number, lot_id, status, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), fixture.tenant(), branch, location, product, number, lot, status);
    }

    private UUID lotId(Fixture fixture, String lotNumber) {
        return jdbc.queryForObject(
                "SELECT id FROM inventory_lots WHERE tenant_id = ? AND product_id = ? AND lot_number = ?",
                UUID.class, fixture.tenant(), fixture.product(), lotNumber);
    }

    private BigDecimal balance(Fixture fixture, UUID location) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenant(), fixture.branch(), fixture.product(), location);
    }

    private BigDecimal lotBalance(Fixture fixture, UUID lot) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_lot_balances WHERE tenant_id = ? AND lot_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenant(), lot, fixture.location());
    }

    private String serialStatus(Fixture fixture, String number) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_serials WHERE tenant_id = ? AND product_id = ? AND serial_number = ?",
                String.class, fixture.tenant(), fixture.product(), number);
    }

    private List<Map<String, Object>> movements(Fixture fixture) {
        return jdbc.queryForList(
                "SELECT type, quantity, reference_type, reference_id FROM inventory_movements WHERE tenant_id = ? AND product_id = ? ORDER BY created_at, id",
                fixture.tenant(), fixture.product());
    }

    private static void assertCode(String expected, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(expected);
    }

    private record Fixture(
            UUID tenant,
            UUID branch,
            UUID secondBranch,
            UUID category,
            UUID unit,
            UUID product,
            UUID user,
            UUID location,
            UUID secondLocation,
            UUID otherBranchLocation) {}
}
