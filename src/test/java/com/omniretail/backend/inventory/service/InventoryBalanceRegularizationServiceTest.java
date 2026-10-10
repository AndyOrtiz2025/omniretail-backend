package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Regularizacion del balance heredado sin ubicacion contra PostgreSQL real. Los escenarios de producto
 * (brocha, martillo) se siembran con datos arbitrarios: ningun identificador de produccion se codifica.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryBalanceRegularizationServiceTest {

    private static final String REGULARIZATION_REFERENCE = "location_regularization";

    @Autowired private InventoryBalanceRegularizationService regularizationService;
    @Autowired private InventoryStockService stockService;
    @Autowired private InventoryReservationLifecycleService lifecycleService;
    @Autowired private InventoryOperationalLocationService operationalLocations;
    @Autowired private InventoryReservationRepository reservations;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;

    // ------------------------------------------------------------ casos de referencia

    @Test
    void consolidatesTheLegacyBalanceIntoTheBalanceOfTheAssignedLocation() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "168.000", "0.000");
        assign(fixture, shelf);
        long movementsBefore = movementCount(fixture);

        LegacyBalanceRegularizationResultResponse result = regularize(fixture, shelf, UUID.randomUUID());

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "268.000", "0.000");
        assertThat(result.idempotent()).isFalse();
        assertThat(result.movedQuantity()).isEqualByComparingTo("100");
        assertThat(result.movedReservedQuantity()).isEqualByComparingTo("0");
        assertThat(result.destinationQuantityBefore()).isEqualByComparingTo("168");
        assertThat(result.destinationQuantityAfter()).isEqualByComparingTo("268");
        assertThat(totalQuantity(fixture)).isEqualByComparingTo("268");
        // El balance NULL se conserva en cero (no se borra) y no se crean mas balances.
        assertThat(countBalances(fixture)).isEqualTo(2);
        assertThat(movementCount(fixture)).isEqualTo(movementsBefore + 1);
        Map<String, Object> movement = jdbc.queryForMap(
                "SELECT type, quantity, from_location_id, to_location_id, reference_id "
                        + "FROM inventory_movements WHERE tenant_id = ? AND reference_type = ?",
                fixture.tenantId(), REGULARIZATION_REFERENCE);
        assertThat(movement.get("type")).isEqualTo("transfer");
        assertThat((BigDecimal) movement.get("quantity")).isEqualByComparingTo("100");
        assertThat(movement.get("from_location_id")).isNull();
        assertThat(movement.get("to_location_id")).isEqualTo(shelf);
        assertThat(movement.get("reference_id")).isEqualTo(result.regularizationId());
        // Ningun movimiento artificial de entrada o salida.
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ? AND type IN ('in', 'out')",
                        Long.class, fixture.tenantId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                        Long.class, fixture.tenantId()))
                .isOne();
    }

    @Test
    void movesReservedQuantityAndRewritesTheAllocationsOfActiveReservations() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "20.000"));
        assertBalance(nullBalance, "100.000", "20.000");
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);

        LegacyBalanceRegularizationResultResponse result = regularize(fixture, shelf, UUID.randomUUID());

        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "100.000", "20.000");
        assertThat(result.movedReservedQuantity()).isEqualByComparingTo("20");
        assertThat(result.reservationsReassigned()).isOne();
        assertThat(allocationField(reservation, "balanceId")).isEqualTo(shelfBalance.toString());
        assertThat(allocationField(reservation, "locationId")).isEqualTo(shelf.toString());
        // La identidad y las cantidades de la reserva no cambian.
        assertThat(reservationRow(reservation, "status")).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_reservations WHERE id = ?",
                        BigDecimal.class, reservation.getId()))
                .isEqualByComparingTo("20");
        assertThat(allocationLength(reservation)).isOne();
    }

    @Test
    void aReservationCanBeConsumedOrReleasedAfterTheRegularization() {
        Fixture fixture = fixture(true, false, false);
        balance(fixture, null, "100.000", "0.000");
        InventoryReservation toConsume = lifecycleService.reserve(reserveCommand(fixture, "20.000"));
        InventoryReservation toRelease = lifecycleService.reserve(reserveCommand(fixture, "5.000"));
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);
        regularize(fixture, shelf, UUID.randomUUID());
        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(shelfBalance, "100.000", "25.000");

        lifecycleService.consume(fixture.tenantId(), toConsume.getId());
        assertBalance(shelfBalance, "80.000", "5.000");
        lifecycleService.release(fixture.tenantId(), toRelease.getId());
        assertBalance(shelfBalance, "80.000", "0.000");

        // Nada vuelve a crearse en el balance heredado.
        assertThat(nullBalanceQuantity(fixture)).isEqualByComparingTo("0");
        assertThat(nullBalanceReserved(fixture)).isEqualByComparingTo("0");
    }

    @Test
    void emptyAllocationsAreRewrittenToAnExplicitDestinationAllocation() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "30.000", "7.000");
        InventoryReservation reservation = persistReservation(fixture, "7.000", "[]", "active");
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);

        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), shelf);
        assertThat(preview.eligible()).isTrue();
        assertThat(preview.emptyAllocationReservations()).isOne();
        regularize(fixture, shelf, UUID.randomUUID());

        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "30.000", "7.000");
        assertThat(allocationLength(reservation)).isOne();
        assertThat(allocationField(reservation, "balanceId")).isEqualTo(shelfBalance.toString());
        assertThat(allocationField(reservation, "locationId")).isEqualTo(shelf.toString());
        assertThat(allocationField(reservation, "reservedQuantity")).isEqualTo("7.000");
        lifecycleService.consume(fixture.tenantId(), reservation.getId());
        assertBalance(shelfBalance, "23.000", "0.000");
    }

    @Test
    void consumedAndReleasedReservationsAreNotTouched() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        String consumedAllocations = "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + nullBalance
                + "\",\"locationId\":null,\"reservedQuantity\":3,\"consumedQuantity\":3}]";
        InventoryReservation consumed = persistReservation(fixture, "3.000", consumedAllocations, "consumed");
        InventoryReservation released = persistReservation(fixture, "2.000", "[]", "released");
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);
        String consumedBefore = rawAllocations(consumed);

        regularize(fixture, shelf, UUID.randomUUID());

        assertThat(rawAllocations(consumed)).isEqualTo(consumedBefore);
        assertThat(rawAllocations(released)).isEqualTo("[]");
        assertThat(reservationRow(consumed, "status")).isEqualTo("consumed");
        assertThat(reservationRow(released, "status")).isEqualTo("released");
        assertBalance(balanceId(fixture, shelf), "10.000", "0.000");
    }

    @Test
    void reservationAcrossBothBalancesKeepsItsDestinationPartAndMovesTheLegacyPart() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "10.000", "4.000");
        UUID shelf = location(fixture, "active");
        UUID shelfBalance = balance(fixture, shelf, "20.000", "6.000");
        assign(fixture, shelf);
        String allocations = "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + nullBalance
                + "\",\"locationId\":null,\"reservedQuantity\":4,\"consumedQuantity\":0},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + shelfBalance
                + "\",\"locationId\":\"" + shelf + "\",\"reservedQuantity\":6,\"consumedQuantity\":0}]";
        InventoryReservation reservation = persistReservation(fixture, "10.000", allocations, "active");

        regularize(fixture, shelf, UUID.randomUUID());

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "30.000", "10.000");
        assertThat(allocationLength(reservation)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_reservations r, jsonb_array_elements(r.allocations) a "
                                + "WHERE r.id = ? AND a->>'balanceId' = ?",
                        Long.class, reservation.getId(), shelfBalance.toString()))
                .isEqualTo(2);
        lifecycleService.consume(fixture.tenantId(), reservation.getId());
        assertBalance(shelfBalance, "20.000", "0.000");
    }

    // ------------------------------------------------------------ bloqueos y 409

    @Test
    void inconsistentReservationsBlockTheOperationWithoutChangingAnything() {
        Fixture drift = fixture(true, false, false);
        UUID driftBalance = balance(drift, null, "10.000", "5.000");
        UUID driftShelf = location(drift, "active");
        assign(drift, driftShelf);
        persistReservation(drift, "3.000", "[]", "active");
        assertRegularizationFails(drift, driftShelf,
                InventoryBalanceRegularizationService.RESERVATION_DRIFT_CODE);
        assertBalance(driftBalance, "10.000", "5.000");

        Fixture malformed = fixture(true, false, false);
        UUID malformedBalance = balance(malformed, null, "10.000", "2.000");
        UUID malformedShelf = location(malformed, "active");
        assign(malformed, malformedShelf);
        persistReservation(malformed, "2.000", "[{\"balanceId\":null,\"reservedQuantity\":2}]", "active");
        assertRegularizationFails(malformed, malformedShelf,
                InventoryBalanceRegularizationService.RESERVATION_INVALID_CODE);
        assertBalance(malformedBalance, "10.000", "2.000");

        Fixture otherBalance = fixture(true, false, false);
        balance(otherBalance, null, "10.000", "2.000");
        UUID otherShelf = location(otherBalance, "active");
        assign(otherBalance, otherShelf);
        persistReservation(otherBalance, "2.000",
                "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + UUID.randomUUID()
                        + "\",\"locationId\":null,\"reservedQuantity\":2,\"consumedQuantity\":0}]",
                "active");
        assertRegularizationFails(otherBalance, otherShelf,
                InventoryBalanceRegularizationService.RESERVATION_OTHER_BALANCE_CODE);

        assertThat(regularizationRows(drift) + regularizationRows(malformed) + regularizationRows(otherBalance))
                .isZero();
        assertThat(movementCount(drift) + movementCount(malformed) + movementCount(otherBalance)).isZero();
    }

    @Test
    void stockInAThirdLocationBlocksTheRegularization() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID other = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        balance(fixture, other, "4.000", "0.000");
        assign(fixture, shelf);

        assertRegularizationFails(fixture, shelf,
                InventoryBalanceRegularizationService.THIRD_LOCATION_STOCK_CODE);
        assertBalance(nullBalance, "10.000", "0.000");
    }

    @Test
    void thereMustBeLegacyStockToRegularize() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "0.000", "0.000");
        balance(fixture, shelf, "5.000", "0.000");
        assign(fixture, shelf);

        assertRegularizationFails(fixture, shelf, InventoryBalanceRegularizationService.NOT_REQUIRED_CODE);
        assertThat(regularizationService.preview(fixture.branchId(), fixture.productId(), shelf).eligible())
                .isFalse();
    }

    @Test
    void destinationMustBeTheAssignedActiveLocationAndLocationsMustBeEnabled() {
        Fixture notAssigned = fixture(true, false, false);
        UUID shelfA = location(notAssigned, "active");
        UUID shelfB = location(notAssigned, "active");
        balance(notAssigned, null, "5.000", "0.000");
        assign(notAssigned, shelfA);
        assertRegularizationFails(notAssigned, shelfB,
                InventoryBalanceRegularizationService.DESTINATION_NOT_ASSIGNED_CODE);

        Fixture inactive = fixture(true, false, false);
        UUID inactiveShelf = location(inactive, "inactive");
        balance(inactive, null, "5.000", "0.000");
        assign(inactive, inactiveShelf);
        assertRegularizationFails(inactive, inactiveShelf,
                InventoryBalanceRegularizationService.DESTINATION_INACTIVE_CODE);

        Fixture unassigned = fixture(true, false, false);
        UUID unassignedShelf = location(unassigned, "active");
        balance(unassigned, null, "5.000", "0.000");
        assertRegularizationFails(unassigned, unassignedShelf,
                InventoryBalanceRegularizationService.DESTINATION_NOT_ASSIGNED_CODE);

        Fixture disabled = fixture(false, false, false);
        UUID disabledShelf = location(disabled, "active");
        balance(disabled, null, "5.000", "0.000");
        assign(disabled, disabledShelf);
        assertRegularizationFails(disabled, disabledShelf,
                InventoryBalanceRegularizationService.LOCATIONS_DISABLED_CODE);
    }

    @Test
    void anotherTenantsProductLocationOrBranchAreNotFoundAndNothingChanges() {
        Fixture fixture = fixture(true, false, false);
        Fixture foreign = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID foreignShelf = location(foreign, "active");
        UUID foreignBalance = balance(foreign, null, "9.000", "0.000");
        balance(fixture, null, "5.000", "0.000");
        assign(fixture, shelf);
        assign(foreign, foreignShelf);
        login(fixture);

        assertCode("PRODUCT_NOT_FOUND",
                () -> regularizationService.preview(fixture.branchId(), foreign.productId(), shelf));
        assertCode("LOCATION_NOT_FOUND",
                () -> regularizationService.preview(fixture.branchId(), fixture.productId(), foreignShelf));
        assertCode("BRANCH_NOT_FOUND",
                () -> regularizationService.preview(foreign.branchId(), fixture.productId(), shelf));
        assertBalance(foreignBalance, "9.000", "0.000");
    }

    @Test
    void aBranchTheUserCannotAccessIsForbiddenForPreviewAndExecution() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "5.000", "0.000");
        assign(fixture, shelf);
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID(), "Motivo");
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));

        assertCode("BRANCH_ACCESS_DENIED",
                () -> regularizationService.preview(fixture.branchId(), fixture.productId(), shelf));
        assertCode("BRANCH_ACCESS_DENIED", () -> regularizationService.regularize(request));
        assertBalance(nullBalance, "5.000", "0.000");
    }

    @Test
    void nonPhysicalProductsAreNotEligible() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "5.000", "0.000");
        assign(fixture, shelf);
        jdbc.update(
                "UPDATE products SET product_type = 'service', tracking_stock = false WHERE id = ?",
                fixture.productId());

        assertCode(InventoryBalanceRegularizationService.PRODUCT_NOT_ELIGIBLE_CODE,
                () -> regularizationService.preview(fixture.branchId(), fixture.productId(), shelf));
    }

    // ------------------------------------------------------------ vista previa, precondiciones, idempotencia

    @Test
    void previewIsReadOnlyAndDescribesTheOutcome() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "20.000");
        persistReservation(fixture, "20.000", "[]", "active");
        UUID shelfBalance = balance(fixture, shelf, "168.000", "0.000");
        assign(fixture, shelf);
        long movementsBefore = movementCount(fixture);
        login(fixture);

        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), shelf);

        assertThat(preview.eligible()).isTrue();
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.sourceQuantity()).isEqualByComparingTo("100");
        assertThat(preview.sourceReservedQuantity()).isEqualByComparingTo("20");
        assertThat(preview.destinationQuantity()).isEqualByComparingTo("168");
        assertThat(preview.resultingQuantity()).isEqualByComparingTo("268");
        assertThat(preview.resultingReservedQuantity()).isEqualByComparingTo("20");
        assertThat(preview.snapshotFingerprint()).hasSize(64);
        assertBalance(nullBalance, "100.000", "20.000");
        assertBalance(shelfBalance, "168.000", "0.000");
        assertThat(movementCount(fixture)).isEqualTo(movementsBefore);
        assertThat(regularizationRows(fixture)).isZero();
    }

    @Test
    void previewReportsBlockersWithoutChangingState() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID other = location(fixture, "active");
        balance(fixture, null, "10.000", "0.000");
        balance(fixture, other, "4.000", "0.000");
        assign(fixture, shelf);
        login(fixture);

        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), shelf);

        assertThat(preview.eligible()).isFalse();
        assertThat(preview.blockers())
                .extracting(LegacyBalanceRegularizationPreviewResponse.Blocker::code)
                .contains(InventoryBalanceRegularizationService.THIRD_LOCATION_STOCK_CODE);
        assertThat(countBalances(fixture)).isEqualTo(2);
    }

    @Test
    void aStalePreviewIsRejectedAndNothingIsApplied() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "168.000", "0.000");
        assign(fixture, shelf);
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID(), "Motivo");
        jdbc.update("UPDATE inventory_balances SET quantity = 90 WHERE id = ?", nullBalance);

        assertCode(InventoryBalanceRegularizationService.STALE_SNAPSHOT_CODE,
                () -> regularizationService.regularize(request));

        assertBalance(nullBalance, "90.000", "0.000");
        assertBalance(shelfBalance, "168.000", "0.000");
        assertThat(regularizationRows(fixture)).isZero();
        assertThat(movementCount(fixture)).isZero();
    }

    @Test
    void aChangeInTheReservationAllocationsMakesTheSnapshotStaleEvenWithEqualTotals() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "10.000", "4.000");
        InventoryReservation reservation = persistReservation(fixture, "4.000", "[]", "active");
        assign(fixture, shelf);
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID(), "Motivo");
        // Mismas cantidades y mismo balance, pero la reserva ya no tiene allocations vacios.
        jdbc.update("UPDATE inventory_reservations SET allocations = ?::jsonb WHERE id = ?",
                "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + nullBalance
                        + "\",\"locationId\":null,\"reservedQuantity\":4,\"consumedQuantity\":0}]",
                reservation.getId());

        assertCode(InventoryBalanceRegularizationService.STALE_SNAPSHOT_CODE,
                () -> regularizationService.regularize(request));

        assertThat(regularizationRows(fixture)).isZero();
        assertBalance(nullBalance, "10.000", "4.000");
    }

    @Test
    void sameKeyAndSameRequestReturnsThePersistedResultWithoutDuplicating() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "168.000", "0.000");
        assign(fixture, shelf);
        UUID key = UUID.randomUUID();
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, key, "Regularizacion");

        LegacyBalanceRegularizationResultResponse first = regularizationService.regularize(request);
        LegacyBalanceRegularizationResultResponse replay = regularizationService.regularize(request);

        assertThat(first.idempotent()).isFalse();
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.regularizationId()).isEqualTo(first.regularizationId());
        assertThat(replay.movementId()).isEqualTo(first.movementId());
        assertThat(replay.movedQuantity()).isEqualByComparingTo(first.movedQuantity());
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "268.000", "0.000");
        assertThat(regularizationRows(fixture)).isOne();
        assertThat(regularizationMovements(fixture)).isOne();
    }

    @Test
    void sameKeyWithADifferentRequestIsRejectedAndADifferentSecondAttemptDoesNotDuplicate() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "100.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "168.000", "0.000");
        assign(fixture, shelf);
        UUID key = UUID.randomUUID();
        regularizationService.regularize(request(fixture, shelf, key, "Primero"));
        RegularizeLegacyBalanceRequest sameKeyOtherReason = new RegularizeLegacyBalanceRequest(
                fixture.branchId(), fixture.productId(), shelf, key, "Otro motivo",
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("168"), "b".repeat(64));

        assertCode(InventoryBalanceRegularizationService.IDEMPOTENCY_KEY_REUSED_CODE,
                () -> regularizationService.regularize(sameKeyOtherReason));
        // Una segunda solicitud legitima con otra clave ya no encuentra saldo heredado.
        assertCode(InventoryBalanceRegularizationService.NOT_REQUIRED_CODE,
                () -> regularizationService.regularize(request(fixture, shelf, UUID.randomUUID(), "Segundo")));

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "268.000", "0.000");
        assertThat(regularizationRows(fixture)).isOne();
        assertThat(regularizationMovements(fixture)).isOne();
    }

    // ------------------------------------------------------------ lotes y series

    @Test
    void mergesCompatibleLotsIntoTheDestinationAndRecordsLotTraces() {
        Fixture fixture = fixture(true, true, false);
        UUID shelf = location(fixture, "active");
        UUID lotA = lot(fixture, "LOT-A");
        UUID lotB = lot(fixture, "LOT-B");
        balance(fixture, null, "10.000", "0.000");
        balance(fixture, shelf, "5.000", "0.000");
        UUID nullLotA = lotBalance(fixture, null, lotA, "6.000");
        UUID nullLotB = lotBalance(fixture, null, lotB, "4.000");
        UUID shelfLotA = lotBalance(fixture, shelf, lotA, "5.000");
        assign(fixture, shelf);

        LegacyBalanceRegularizationResultResponse result = regularize(fixture, shelf, UUID.randomUUID());

        assertThat(result.lotBalancesMerged()).isEqualTo(2);
        assertThat(lotQuantity(nullLotA)).isEqualByComparingTo("0");
        assertThat(lotQuantity(nullLotB)).isEqualByComparingTo("0");
        assertThat(lotQuantity(shelfLotA)).isEqualByComparingTo("11");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE tenant_id = ? AND lot_id = ? "
                                + "AND location_id = ?",
                        BigDecimal.class, fixture.tenantId(), lotB, shelf))
                .isEqualByComparingTo("4");
        assertBalance(balanceId(fixture, shelf), "15.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(trace.quantity), 0) FROM inventory_movement_traces trace "
                                + "JOIN inventory_movements movement ON movement.id = trace.movement_id "
                                + "WHERE trace.tenant_id = ? AND movement.reference_type = ? "
                                + "AND trace.lot_id IS NOT NULL",
                        BigDecimal.class, fixture.tenantId(), REGULARIZATION_REFERENCE))
                .isEqualByComparingTo("10");
    }

    @Test
    void relocatesPhysicalSerialsToTheDestinationAndRecordsSerialTraces() {
        Fixture fixture = fixture(true, false, true);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "2.000", "0.000");
        serial(fixture, null, "SER-1", "AVAILABLE");
        serial(fixture, null, "SER-2", "AVAILABLE");
        assign(fixture, shelf);

        LegacyBalanceRegularizationResultResponse result = regularize(fixture, shelf, UUID.randomUUID());

        assertThat(result.serialsRelocated()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_serials WHERE tenant_id = ? AND product_id = ? "
                                + "AND location_id = ?",
                        Long.class, fixture.tenantId(), fixture.productId(), shelf))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_serials WHERE tenant_id = ? AND location_id IS NULL",
                        Long.class, fixture.tenantId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_movement_traces trace "
                                + "JOIN inventory_movements movement ON movement.id = trace.movement_id "
                                + "WHERE trace.tenant_id = ? AND movement.reference_type = ? "
                                + "AND trace.serial_id IS NOT NULL",
                        Long.class, fixture.tenantId(), REGULARIZATION_REFERENCE))
                .isEqualTo(2);
        assertBalance(balanceId(fixture, shelf), "2.000", "0.000");
    }

    @Test
    void incompatibleTraceabilityRollsBackEverything() {
        Fixture lots = fixture(true, true, false);
        UUID lotsShelf = location(lots, "active");
        UUID lotsNull = balance(lots, null, "10.000", "0.000");
        UUID lotA = lot(lots, "LOT-A");
        lotBalance(lots, null, lotA, "6.000");
        assign(lots, lotsShelf);
        assertRegularizationFails(lots, lotsShelf,
                InventoryBalanceRegularizationService.TRACEABILITY_INCONSISTENT_CODE);
        assertBalance(lotsNull, "10.000", "0.000");

        Fixture serials = fixture(true, false, true);
        UUID serialsShelf = location(serials, "active");
        UUID serialsNull = balance(serials, null, "2.000", "0.000");
        serial(serials, null, "ONLY-ONE", "AVAILABLE");
        assign(serials, serialsShelf);
        assertRegularizationFails(serials, serialsShelf,
                InventoryBalanceRegularizationService.TRACEABILITY_INCONSISTENT_CODE);
        assertBalance(serialsNull, "2.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_serials WHERE tenant_id = ? AND location_id IS NULL",
                        Long.class, serials.tenantId()))
                .isOne();

        assertThat(regularizationRows(lots) + regularizationRows(serials)).isZero();
        assertThat(movementCount(lots) + movementCount(serials)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ? AND location_id = ?",
                        Long.class, lots.tenantId(), lotsShelf))
                .isZero();
    }

    @Test
    void traceableProductsWithReservationsOverTheLegacyBalanceAreBlocked() {
        Fixture fixture = fixture(true, true, false);
        UUID shelf = location(fixture, "active");
        UUID lotA = lot(fixture, "LOT-A");
        UUID nullBalance = balance(fixture, null, "5.000", "2.000");
        lotBalance(fixture, null, lotA, "5.000");
        persistReservation(fixture, "2.000", "[]", "active");
        assign(fixture, shelf);

        assertRegularizationFails(fixture, shelf,
                InventoryBalanceRegularizationService.TRACEABLE_RESERVATION_CODE);
        assertBalance(nullBalance, "5.000", "2.000");
    }

    // ------------------------------------------------------------ devoluciones historicas

    @Test
    void aHistoricalVoidOfALegacySaleRestoresToTheRegularizedDestinationNotToTheLegacyBalance() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, lineId);
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);
        regularize(fixture, shelf, UUID.randomUUID());
        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(shelfBalance, "6.000", "0.000");

        stockService.incrementStock(restoreCommand(fixture, "4.000", "POS_SALE_VOID", saleId, lineId));

        assertBalance(shelfBalance, "10.000", "0.000");
        assertBalance(nullBalance, "0.000", "0.000");
        assertThat(countBalances(fixture)).isEqualTo(2);
    }

    @Test
    void aHistoricalReturnIsBlockedWhenTheRegularizedDestinationIsNoLongerOperational() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, lineId);
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);
        regularize(fixture, shelf, UUID.randomUUID());
        UUID shelfBalance = balanceId(fixture, shelf);
        UUID newShelf = location(fixture, "active");
        jdbc.update(
                "UPDATE product_inventory_settings SET default_location_id = ? "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                newShelf, fixture.tenantId(), fixture.branchId(), fixture.productId());

        assertCode(InventoryOperationalLocationService.RESTORE_ORIGIN_INVALID_CODE,
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "4.000", "POS_SALE_VOID", saleId, lineId)));

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "6.000", "0.000");
    }

    @Test
    void aHistoricalReturnIsAmbiguousWhenLaterRegularizationsTargetDifferentLocations() {
        Fixture fixture = fixture(true, false, false);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "4.000", "POS_SALE", saleId, lineId);
        UUID shelfA = location(fixture, "active");
        assign(fixture, shelfA);
        regularize(fixture, shelfA, UUID.randomUUID());
        UUID shelfABalance = balanceId(fixture, shelfA);
        // El saldo heredado reaparece y el producto se reasigna: una segunda regularizacion apunta a otra ubicacion.
        jdbc.update("UPDATE inventory_balances SET quantity = 0 WHERE id = ?", shelfABalance);
        jdbc.update("UPDATE inventory_balances SET quantity = 3 WHERE id = ?", nullBalance);
        UUID shelfB = location(fixture, "active");
        jdbc.update(
                "UPDATE product_inventory_settings SET default_location_id = ? "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                shelfB, fixture.tenantId(), fixture.branchId(), fixture.productId());
        regularize(fixture, shelfB, UUID.randomUUID());
        UUID shelfBBalance = balanceId(fixture, shelfB);

        assertCode(InventoryOperationalLocationService.RESTORE_ORIGIN_AMBIGUOUS_CODE,
                () -> stockService.incrementStock(
                        restoreCommand(fixture, "4.000", "POS_SALE_VOID", saleId, lineId)));

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfABalance, "0.000", "0.000");
        assertBalance(shelfBBalance, "3.000", "0.000");
    }

    @Test
    void aSaleMadeAfterTheRegularizationRestoresToItsOwnBalance() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        assign(fixture, shelf);
        regularize(fixture, shelf, UUID.randomUUID());
        UUID shelfBalance = balanceId(fixture, shelf);
        UUID saleId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        sell(fixture, "3.000", "POS_SALE", saleId, lineId);
        assertBalance(shelfBalance, "7.000", "0.000");

        stockService.incrementStock(restoreCommand(fixture, "3.000", "POS_SALE_VOID", saleId, lineId));

        assertBalance(shelfBalance, "10.000", "0.000");
        assertBalance(nullBalance, "0.000", "0.000");
    }

    // ------------------------------------------------------------ Picking activo

    @Test
    void anActivePickingLineAtAnotherLocationBlocksTheRegularization() {
        Fixture fixture = fixture(true, false, false);
        // El reservado nace de reserve(): sembrarlo ademas duplicaria las 3 unidades (6 reservadas, 3 en reservas).
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "3.000"));
        UUID shelf = location(fixture, "active");
        UUID elsewhere = location(fixture, "active");
        assign(fixture, shelf);
        picking(fixture, reservation, elsewhere, null);

        assertRegularizationFails(fixture, shelf,
                InventoryBalanceRegularizationService.PICKING_CONFLICT_CODE);
        assertBalance(nullBalance, "10.000", "3.000");
        assertThat(allocationField(reservation, "balanceId")).isEqualTo(nullBalance.toString());
    }

    @Test
    void anActivePickingLineWithPhysicalSelectionBlocksTheRegularization() {
        Fixture fixture = fixture(true, false, false);
        balance(fixture, null, "10.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "3.000"));
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);
        picking(fixture, reservation, null, "[{\"lotId\":\"" + UUID.randomUUID() + "\",\"quantity\":3}]");

        assertRegularizationFails(fixture, shelf,
                InventoryBalanceRegularizationService.PICKING_CONFLICT_CODE);
    }

    @Test
    void anActivePickingLineWithoutLocationIsCompatibleWithTheRegularization() {
        Fixture fixture = fixture(true, false, false);
        balance(fixture, null, "10.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(reserveCommand(fixture, "3.000"));
        UUID shelf = location(fixture, "active");
        assign(fixture, shelf);
        picking(fixture, reservation, null, null);

        regularize(fixture, shelf, UUID.randomUUID());

        assertBalance(balanceId(fixture, shelf), "10.000", "3.000");
        assertThat(allocationField(reservation, "locationId")).isEqualTo(shelf.toString());
    }

    // ------------------------------------------------------------ compatibilidad posterior

    @Test
    void laterReceiptsAndOperationalStockUseOnlyTheDestinationBalance() {
        Fixture fixture = fixture(true, false, false);
        UUID shelf = location(fixture, "active");
        balance(fixture, null, "100.000", "0.000");
        balance(fixture, shelf, "168.000", "0.000");
        assign(fixture, shelf);
        regularize(fixture, shelf, UUID.randomUUID());

        stockService.incrementStock(addCommand(fixture, "12.000"));

        assertBalance(balanceId(fixture, shelf), "280.000", "0.000");
        assertThat(nullBalanceQuantity(fixture)).isEqualByComparingTo("0");
        assertThat(countBalances(fixture)).isEqualTo(2);
        assertThat(operationalLocations.availableQuantity(
                        fixture.tenantId(), fixture.branchId(), fixture.productId()))
                .isEqualByComparingTo("280");
    }

    // ------------------------------------------------------------ utilidades

    private LegacyBalanceRegularizationResultResponse regularize(
            Fixture fixture, UUID location, UUID key) {
        return regularizationService.regularize(request(fixture, location, key, "Regularizacion heredada"));
    }

    private RegularizeLegacyBalanceRequest request(
            Fixture fixture, UUID location, UUID key, String reason) {
        login(fixture);
        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), location);
        return new RegularizeLegacyBalanceRequest(
                fixture.branchId(), fixture.productId(), location, key, reason,
                preview.sourceQuantity(), preview.sourceReservedQuantity(),
                preview.destinationQuantity(), preview.snapshotFingerprint());
    }

    private void assertRegularizationFails(Fixture fixture, UUID location, String code) {
        RegularizeLegacyBalanceRequest request = request(fixture, location, UUID.randomUUID(), "Motivo");
        assertCode(code, () -> regularizationService.regularize(request));
    }

    private static void assertCode(String code, ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private void login(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, null,
                fixture.branchId(), UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    private void sell(
            Fixture fixture, String quantity, String referenceType, UUID saleId, UUID lineId) {
        stockService.deductStock(new DeductStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal(quantity),
                "Venta", referenceType, saleId, lineId, null));
    }

    private static AddStockCommand restoreCommand(
            Fixture fixture, String quantity, String referenceType, UUID referenceId, UUID lineId) {
        return new AddStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal(quantity),
                "Restauracion", referenceType, referenceId, lineId, null);
    }

    private static AddStockCommand addCommand(Fixture fixture, String quantity) {
        return new AddStockCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal(quantity),
                "Entrada", "MANUAL_ADJUSTMENT", UUID.randomUUID(), null, null);
    }

    private static ReserveInventoryCommand reserveCommand(Fixture fixture, String quantity) {
        return new ReserveInventoryCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(),
                InventoryReservationSourceType.transfer, UUID.randomUUID(), UUID.randomUUID(),
                null, null, new BigDecimal(quantity));
    }

    private InventoryReservation persistReservation(
            Fixture fixture, String quantity, String allocations, String status) {
        InventoryReservation reservation = InventoryReservation.builder()
                .branchId(fixture.branchId())
                .sourceType(InventoryReservationSourceType.transfer)
                .sourceId(UUID.randomUUID())
                .sourceLineId(UUID.randomUUID())
                .productId(fixture.productId())
                .quantity(new BigDecimal(quantity))
                .status(InventoryReservationStatus.valueOf(status))
                .allocations(allocations)
                .build();
        reservation.setTenantId(fixture.tenantId());
        return reservations.saveAndFlush(reservation);
    }

    private void picking(
            Fixture fixture, InventoryReservation reservation, UUID itemLocation, String pickedTraces) {
        UUID pickingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO picking_orders (id, tenant_id, branch_id, source_type, source_id, status, priority)
                VALUES (?, ?, ?, 'transfer', ?, 'in_progress', 'normal')
                """, pickingId, fixture.tenantId(), fixture.branchId(), reservation.getSourceId());
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, product_id, requested_quantity,
                     picked_quantity, location_id, picked_traces, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?::jsonb, 'pending')
                """, UUID.randomUUID(), fixture.tenantId(), pickingId, reservation.getSourceLineId(),
                fixture.productId(), reservation.getQuantity(), itemLocation, pickedTraces);
    }

    private Fixture fixture(boolean locationsEnabled, boolean trackingLot, boolean trackingSerial) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbc.update(
                "INSERT INTO business_capabilities_configs "
                        + "(id, tenant_id, preset, supports_multiple_locations) VALUES (?, ?, 'custom', ?)",
                UUID.randomUUID(), tenantId, locationsEnabled);
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
                        + "tracking_stock, tracking_lot, tracking_serial) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'physical', true, ?, ?)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId,
                trackingLot, trackingSerial);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) "
                        + "VALUES (?, ?, 'Administrador', ?, 'employee', 'active', ?)",
                userId, tenantId, userId + "@test.local", branchId);
        Fixture fixture = new Fixture(tenantId, branchId, productId, userId);
        login(fixture);
        return fixture;
    }

    private UUID location(Fixture fixture, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'Estante', 'warehouse', ?)",
                id, fixture.tenantId(), fixture.branchId(), "LOC-" + id, status);
        return id;
    }

    private void assign(Fixture fixture, UUID locationId) {
        jdbc.update(
                "INSERT INTO product_inventory_settings "
                        + "(tenant_id, branch_id, product_id, default_location_id) VALUES (?, ?, ?, ?)",
                fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private UUID balance(Fixture fixture, UUID locationId, String quantity, String reserved) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_balances "
                        + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId,
                new BigDecimal(quantity), new BigDecimal(reserved));
        return id;
    }

    private UUID lot(Fixture fixture, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                id, fixture.tenantId(), fixture.productId(), number);
        return id;
    }

    private UUID lotBalance(Fixture fixture, UUID locationId, UUID lotId, String quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_lot_balances "
                        + "(id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, fixture.tenantId(), fixture.branchId(), locationId, lotId, new BigDecimal(quantity));
        return id;
    }

    private void serial(Fixture fixture, UUID locationId, String number, String status) {
        jdbc.update(
                "INSERT INTO inventory_serials "
                        + "(id, tenant_id, branch_id, location_id, product_id, serial_number, status, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId,
                fixture.productId(), number, status);
    }

    private UUID balanceId(Fixture fixture, UUID locationId) {
        String sql = locationId == null
                ? "SELECT id FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id IS NULL"
                : "SELECT id FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id = ?";
        return locationId == null
                ? jdbc.queryForObject(sql, UUID.class, fixture.tenantId(), fixture.branchId(), fixture.productId())
                : jdbc.queryForObject(
                        sql, UUID.class, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private void assertBalance(UUID balanceId, String quantity, String reserved) {
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_balances WHERE id = ?", BigDecimal.class, balanceId))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                        BigDecimal.class, balanceId))
                .isEqualByComparingTo(reserved);
    }

    private BigDecimal lotQuantity(UUID lotBalanceId) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_lot_balances WHERE id = ?", BigDecimal.class, lotBalanceId);
    }

    private BigDecimal totalQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_balances "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private BigDecimal nullBalanceQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id IS NULL",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private BigDecimal nullBalanceReserved(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id IS NULL",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private long countBalances(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ?",
                Long.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private long movementCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ?",
                Long.class, fixture.tenantId());
    }

    private long regularizationMovements(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = ?",
                Long.class, fixture.tenantId(), REGULARIZATION_REFERENCE);
    }

    private long regularizationRows(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                Long.class, fixture.tenantId());
    }

    private String allocationField(InventoryReservation reservation, String field) {
        return jdbc.queryForObject(
                "SELECT allocations -> 0 ->> ? FROM inventory_reservations WHERE id = ?",
                String.class, field, reservation.getId());
    }

    private int allocationLength(InventoryReservation reservation) {
        return jdbc.queryForObject(
                "SELECT jsonb_array_length(allocations) FROM inventory_reservations WHERE id = ?",
                Integer.class, reservation.getId());
    }

    private String rawAllocations(InventoryReservation reservation) {
        return jdbc.queryForObject(
                "SELECT allocations::text FROM inventory_reservations WHERE id = ?",
                String.class, reservation.getId());
    }

    private String reservationRow(InventoryReservation reservation, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM inventory_reservations WHERE id = ?",
                String.class, reservation.getId());
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID userId) {}
}
