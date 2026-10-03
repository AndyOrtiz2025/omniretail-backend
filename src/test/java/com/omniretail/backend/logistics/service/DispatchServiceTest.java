package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DispatchServiceTest {

    @Autowired private DispatchService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoSpyBean private InventoryMovementRepository movements;

    @Test
    void confirmPersistsCanonicalDispatchPackagesAndCompleteInventoryEffects() {
        DispatchTestFixture.Data fixture = fixture(2);

        DispatchResponse result = service.confirm(
                fixture.branchId(), fixture.orderId(), request("confirm-complete", explicitPackages()));

        assertThat(result.orderId()).isEqualTo(fixture.orderId());
        assertThat(result.orderStatus().name()).isEqualTo("dispatched");
        assertThat(result.dispatchStatus().name()).isEqualTo("dispatched");
        assertThat(result.carrierName()).isEqualTo("Cargo Express");
        assertThat(result.trackingNumber()).isEqualTo("GUIA-001");
        assertThat(result.packages()).extracting(p -> p.number()).containsExactly("PKG-1", "PKG-2");
        assertThat(count("dispatches", "order_id", fixture.orderId())).isOne();
        assertThat(text("orders", "status", "id", fixture.orderId())).isEqualTo("dispatched");
        assertThat(text("inventory_reservations", "status", "id", fixture.reservationId()))
                .isEqualTo("consumed");
        assertBalance(fixture, "5.000", "0.000");

        MovementRow movement = jdbc.queryForObject("""
                SELECT tenant_id, branch_id, product_id, type, quantity, quantity_before,
                       quantity_after, from_location_id, to_location_id, reference_type,
                       reference_id, performed_by_user_id
                FROM inventory_movements WHERE reference_id = ?
                """, (rs, row) -> new MovementRow(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getString("type"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("quantity_before"),
                        rs.getBigDecimal("quantity_after"), rs.getObject("from_location_id", UUID.class),
                        rs.getObject("to_location_id", UUID.class), rs.getString("reference_type"),
                        rs.getObject("reference_id", UUID.class),
                        rs.getObject("performed_by_user_id", UUID.class)), result.dispatchId());
        assertThat(movement.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(movement.branchId()).isEqualTo(fixture.branchId());
        assertThat(movement.productId()).isEqualTo(fixture.productId());
        assertThat(movement.type()).isEqualTo("out");
        assertThat(movement.quantity()).isEqualByComparingTo("5.000");
        assertThat(movement.quantityBefore()).isEqualByComparingTo("10.000");
        assertThat(movement.quantityAfter()).isEqualByComparingTo("5.000");
        assertThat(movement.fromLocationId()).isEqualTo(fixture.locationId());
        assertThat(movement.toLocationId()).isNull();
        assertThat(movement.referenceType()).isEqualTo("dispatch");
        assertThat(movement.referenceId()).isEqualTo(result.dispatchId());
        assertThat(movement.performedByUserId()).isEqualTo(fixture.userId());
    }

    @Test
    void queueAndDetailExposeOnlyTheCanonicalDispatch() {
        DispatchTestFixture.Data fixture = fixture(2);
        assertThat(service.getQueue(fixture.branchId()))
                .singleElement()
                .satisfies(row -> assertThat(row.orderId()).isEqualTo(fixture.orderId()));

        DispatchResponse confirmed = service.confirm(
                fixture.branchId(), fixture.orderId(), request("read-dispatch", explicitPackages()));

        DispatchResponse detail = service.getDetail(fixture.branchId(), fixture.orderId());
        assertThat(detail.dispatchId()).isEqualTo(confirmed.dispatchId());
        assertThat(detail.packages()).extracting(p -> p.number()).containsExactly("PKG-1", "PKG-2");
    }

    @Test
    void oneMovementIsPersistedForEachConsumedAllocation() {
        DispatchTestFixture.Data fixture = fixture(2);
        UUID secondLocation = UUID.randomUUID();
        UUID secondBalance = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Segunda bodega', 'warehouse', 'active')
                """, secondLocation, fixture.tenantId(), fixture.branchId(),
                "LOC-" + secondLocation.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 7.000, 2.000)
                """, secondBalance, fixture.tenantId(), fixture.branchId(), fixture.productId(),
                secondLocation);
        jdbc.update("UPDATE inventory_balances SET reserved_quantity = 3.000 WHERE id = ?",
                fixture.balanceId());
        String allocations = "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\""
                + fixture.balanceId() + "\",\"locationId\":\"" + fixture.locationId()
                + "\",\"reservedQuantity\":3.000,\"consumedQuantity\":0.000},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\""
                + secondBalance + "\",\"locationId\":\"" + secondLocation
                + "\",\"reservedQuantity\":2.000,\"consumedQuantity\":0.000}]";
        jdbc.update("UPDATE inventory_reservations SET allocations = ?::jsonb WHERE id = ?",
                allocations, fixture.reservationId());

        DispatchResponse result = service.confirm(
                fixture.branchId(), fixture.orderId(), request("multiple-allocations", explicitPackages()));

        assertBalance(fixture, "7.000", "0.000");
        BalanceRow second = jdbc.queryForObject(
                "SELECT quantity, reserved_quantity FROM inventory_balances WHERE id = ?",
                (rs, row) -> new BalanceRow(
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("reserved_quantity")),
                secondBalance);
        assertThat(second.quantity()).isEqualByComparingTo("5.000");
        assertThat(second.reserved()).isEqualByComparingTo("0.000");
        assertThat(count("inventory_movements", "reference_id", result.dispatchId())).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT quantity, quantity_before, quantity_after, from_location_id
                FROM inventory_movements WHERE reference_id = ? ORDER BY quantity
                """, result.dispatchId()))
                .allSatisfy(row -> assertThat(row.get("from_location_id")).isIn(
                        fixture.locationId(), secondLocation));
    }

    @Test
    void omittedPackagesAreDerivedFromFinalPackingLabel() {
        DispatchTestFixture.Data fixture = fixture(2);

        DispatchResponse result = service.confirm(
                fixture.branchId(), fixture.orderId(), request("derived-packages", null));

        assertThat(result.packages()).extracting(p -> p.number())
                .containsExactly(fixture.labelCode() + "-1", fixture.labelCode() + "-2");
        assertThat(result.packages()).extracting(p -> p.description())
                .containsExactly("Bulto 1 de 2", "Bulto 2 de 2");
    }

    @Test
    void explicitPackageCountMustMatchPacking() {
        DispatchTestFixture.Data fixture = fixture(2);

        assertCode(() -> service.confirm(fixture.branchId(), fixture.orderId(),
                request("bad-package-count", List.of(explicitPackages().getFirst()))), "BAD_REQUEST");

        assertUntouched(fixture);
    }

    @Test
    void thirdPartyRequiresCarrierAndTracking() {
        DispatchTestFixture.Data missingCarrier = fixture(2);
        ConfirmDispatchRequest noCarrier = new ConfirmDispatchRequest(
                "missing-carrier", null, "GUIA-001", explicitPackages());
        assertCode(() -> service.confirm(missingCarrier.branchId(), missingCarrier.orderId(), noCarrier),
                "BAD_REQUEST");
        assertUntouched(missingCarrier);

        DispatchTestFixture.Data missingTracking = fixture(2);
        ConfirmDispatchRequest noTracking = new ConfirmDispatchRequest(
                "missing-tracking", "Cargo Express", null, explicitPackages());
        assertCode(() -> service.confirm(missingTracking.branchId(), missingTracking.orderId(), noTracking),
                "BAD_REQUEST");
        assertUntouched(missingTracking);
    }

    @Test
    void retryReturnsPersistedHistoricalSnapshotWithoutRepeatingEffects() {
        DispatchTestFixture.Data fixture = fixture(2);
        ConfirmDispatchRequest request = request("same-operation", explicitPackages());
        DispatchResponse first = service.confirm(fixture.branchId(), fixture.orderId(), request);
        jdbc.update("UPDATE dispatches SET carrier_name = 'CURRENT VALUE' WHERE id = ?", first.dispatchId());

        DispatchResponse retry = service.confirm(fixture.branchId(), fixture.orderId(), request);

        assertThat(retry.idempotent()).isTrue();
        assertThat(retry.dispatchId()).isEqualTo(first.dispatchId());
        assertThat(retry.carrierName()).isEqualTo("Cargo Express");
        assertThat(retry.trackingNumber()).isEqualTo("GUIA-001");
        assertThat(retry.dispatchedAt()).isEqualTo(first.dispatchedAt());
        assertThat(retry.packages()).isEqualTo(first.packages());
        assertThat(count("dispatches", "order_id", fixture.orderId())).isOne();
        assertThat(count("dispatch_operations", "dispatch_id", first.dispatchId())).isOne();
        assertThat(count("inventory_movements", "reference_id", first.dispatchId())).isOne();
        assertBalance(fixture, "5.000", "0.000");
    }

    @Test
    void operationIdCannotBeReusedWithDifferentPayload() {
        DispatchTestFixture.Data fixture = fixture(2);
        service.confirm(fixture.branchId(), fixture.orderId(), request("fingerprint", explicitPackages()));
        ConfirmDispatchRequest changed = new ConfirmDispatchRequest(
                "fingerprint", "Another carrier", "ANOTHER-GUIDE", explicitPackages());

        assertCode(() -> service.confirm(fixture.branchId(), fixture.orderId(), changed),
                "DISPATCH_OPERATION_ID_REUSED");
    }

    @Test
    void failureAfterInventoryConsumptionRollsBackEveryCriticalEffect() {
        DispatchTestFixture.Data fixture = fixture(2);
        doThrow(new IllegalStateException("forced movement failure"))
                .when(movements).save(any(InventoryMovement.class));

        assertThatThrownBy(() -> service.confirm(
                fixture.branchId(), fixture.orderId(), request("rollback", explicitPackages())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced movement failure");
        reset(movements);

        assertUntouched(fixture);
        assertThat(count("dispatch_operations", "tenant_id", fixture.tenantId())).isZero();
        assertThat(count("inventory_movements", "tenant_id", fixture.tenantId())).isZero();
    }

    @Test
    void traceableProductWithoutCompletedPhysicalSelectionIsRejectedWithoutSideEffect() {
        DispatchTestFixture.Data fixture = fixture(2);
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.productId());

        assertCode(() -> service.confirm(fixture.branchId(), fixture.orderId(),
                request("traceability", explicitPackages())),
                "PICKING_TRACE_HISTORY_INCONSISTENT");

        assertUntouched(fixture);
    }

    @Test
    void traceableLotDispatchConsumesAggregateAndPhysicalReservationExactlyOnce() {
        DispatchTestFixture.Data fixture = fixture(2);
        UUID lot = UUID.randomUUID();
        UUID picking = jdbc.queryForObject(
                "SELECT picking_order_id FROM packings WHERE id = ?",
                UUID.class,
                fixture.packingId());
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, 'DISPATCH-LOT')
                """, lot, fixture.tenantId(), fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 5.000, 5.000)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(),
                fixture.locationId(), lot);
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id,
                     product_id, requested_quantity, picked_quantity, location_id,
                     picked_traces, status)
                VALUES (?, ?, ?, ?, ?, ?, 5.000, 5.000, ?, ?::jsonb, 'completed')
                """, UUID.randomUUID(), fixture.tenantId(), picking,
                fixture.orderItemId(), fixture.orderItemId(), fixture.productId(),
                fixture.locationId(),
                "[{\"locationId\":\"" + fixture.locationId()
                        + "\",\"lotId\":\"" + lot
                        + "\",\"quantity\":5.000,\"serialNumbers\":[]}]");

        DispatchResponse result = service.confirm(
                fixture.branchId(), fixture.orderId(),
                request("traceable-lot", explicitPackages()));

        assertBalance(fixture, "5.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        lot))
                .isEqualByComparingTo("0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        lot))
                .isEqualByComparingTo("0.000");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM inventory_movement_traces trace
                        JOIN inventory_movements movement ON movement.id = trace.movement_id
                        WHERE movement.reference_id = ?
                          AND movement.reference_line_id = ?
                          AND trace.lot_id = ?
                        """, Long.class, result.dispatchId(), fixture.orderItemId(), lot))
                .isOne();
    }

    @Test
    void traceableSerialDispatchConsumesReservedSerialsAndWritesSerialOnlyTraces() {
        DispatchTestFixture.Data fixture = fixture(2);
        UUID picking = jdbc.queryForObject(
                "SELECT picking_order_id FROM packings WHERE id = ?",
                UUID.class,
                fixture.packingId());
        jdbc.update("UPDATE products SET tracking_serial = true WHERE id = ?", fixture.productId());
        for (int index = 1; index <= 5; index++) {
            jdbc.update(
                    """
                    INSERT INTO inventory_serials
                        (id, tenant_id, branch_id, location_id, product_id,
                         serial_number, status, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'RESERVED', 0)
                    """,
                    UUID.randomUUID(),
                    fixture.tenantId(),
                    fixture.branchId(),
                    fixture.locationId(),
                    fixture.productId(),
                    "ORDER-SER-" + index);
        }
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id,
                     product_id, requested_quantity, picked_quantity, location_id,
                     picked_traces, status)
                VALUES (?, ?, ?, ?, ?, ?, 5.000, 5.000, ?, ?::jsonb, 'completed')
                """, UUID.randomUUID(), fixture.tenantId(), picking,
                fixture.orderItemId(), fixture.orderItemId(), fixture.productId(),
                fixture.locationId(),
                "[{\"locationId\":\"" + fixture.locationId()
                        + "\",\"lotId\":null,\"quantity\":5.000,\"serialNumbers\":["
                        + "\"ORDER-SER-1\",\"ORDER-SER-2\",\"ORDER-SER-3\","
                        + "\"ORDER-SER-4\",\"ORDER-SER-5\"]}]");

        DispatchResponse result = service.confirm(
                fixture.branchId(), fixture.orderId(),
                request("traceable-serial", explicitPackages()));
        DispatchResponse replay = service.confirm(
                fixture.branchId(), fixture.orderId(),
                request("traceable-serial", explicitPackages()));

        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.dispatchId()).isEqualTo(result.dispatchId());
        assertBalance(fixture, "5.000", "0.000");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM inventory_serials
                        WHERE tenant_id = ? AND product_id = ? AND status = 'CONSUMED'
                        """,
                        Long.class,
                        fixture.tenantId(),
                        fixture.productId()))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM inventory_movement_traces trace
                        JOIN inventory_movements movement ON movement.id = trace.movement_id
                        WHERE movement.reference_id = ?
                          AND movement.reference_line_id = ?
                          AND trace.serial_id IS NOT NULL
                          AND trace.lot_id IS NULL
                        """,
                        Long.class,
                        result.dispatchId(),
                        fixture.orderItemId()))
                .isEqualTo(5L);
    }

    @Test
    void tenantAndBranchBoundariesAreEnforced() {
        DispatchTestFixture.Data foreignTenant = DispatchTestFixture.create(jdbc, 2);
        actor(UUID.randomUUID(), foreignTenant.branchId(), foreignTenant.userId(), true);
        assertCode(() -> service.confirm(foreignTenant.branchId(), foreignTenant.orderId(),
                request("foreign-tenant", explicitPackages())), "ORDER_NOT_FOUND");
        assertUntouched(foreignTenant);

        DispatchTestFixture.Data deniedBranch = DispatchTestFixture.create(jdbc, 2);
        actor(deniedBranch.tenantId(), deniedBranch.branchId(), deniedBranch.userId(), false);
        assertCode(() -> service.confirm(deniedBranch.branchId(), deniedBranch.orderId(),
                request("denied-branch", explicitPackages())), "BRANCH_ACCESS_DENIED");
        assertUntouched(deniedBranch);

        DispatchTestFixture.Data crossedBranch = DispatchTestFixture.create(jdbc, 2);
        actor(crossedBranch.tenantId(), crossedBranch.branchId(), crossedBranch.userId(), true);
        assertCode(() -> service.confirm(UUID.randomUUID(), crossedBranch.orderId(),
                request("crossed-branch", explicitPackages())), "ORDER_NOT_FOUND");
        assertUntouched(crossedBranch);
    }

    @Test
    void dispatchDetailCannotBeReadAcrossTenantOrBranchBoundaries() {
        DispatchTestFixture.Data fixture = fixture(2);
        service.confirm(fixture.branchId(), fixture.orderId(), request("detail-scope", explicitPackages()));

        actor(UUID.randomUUID(), fixture.branchId(), fixture.userId(), true);
        assertCode(() -> service.getDetail(fixture.branchId(), fixture.orderId()), "DISPATCH_NOT_FOUND");

        actor(fixture.tenantId(), fixture.branchId(), fixture.userId(), true);
        assertCode(() -> service.getDetail(UUID.randomUUID(), fixture.orderId()), "DISPATCH_NOT_FOUND");
    }

    private DispatchTestFixture.Data fixture(int packages) {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, packages);
        actor(fixture.tenantId(), fixture.branchId(), fixture.userId(), true);
        return fixture;
    }

    private void actor(UUID tenant, UUID branch, UUID user, boolean allBranches) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                user, tenant, UserType.employee, null, branch, UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(
                new BranchAccess(allBranches, allBranches ? Set.of() : Set.of()));
    }

    private ConfirmDispatchRequest request(
            String operationId, List<ConfirmDispatchRequest.PackageRequest> packages) {
        return new ConfirmDispatchRequest(
                operationId, "Cargo Express", "GUIA-001", packages);
    }

    private List<ConfirmDispatchRequest.PackageRequest> explicitPackages() {
        return List.of(
                new ConfirmDispatchRequest.PackageRequest("PKG-1", new BigDecimal("2.000"), "Caja 1"),
                new ConfirmDispatchRequest.PackageRequest("PKG-2", new BigDecimal("2.500"), "Caja 2"));
    }

    private void assertCode(ThrowingCall call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(code);
    }

    private void assertUntouched(DispatchTestFixture.Data fixture) {
        assertThat(text("orders", "status", "id", fixture.orderId())).isEqualTo("ready_for_dispatch");
        assertThat(text("inventory_reservations", "status", "id", fixture.reservationId()))
                .isEqualTo("active");
        assertBalance(fixture, "10.000", "5.000");
        assertThat(count("dispatches", "tenant_id", fixture.tenantId())).isZero();
        assertThat(count("inventory_movements", "tenant_id", fixture.tenantId())).isZero();
    }

    private void assertBalance(DispatchTestFixture.Data fixture, String quantity, String reserved) {
        BalanceRow row = jdbc.queryForObject(
                "SELECT quantity, reserved_quantity FROM inventory_balances WHERE id = ?",
                (rs, number) -> new BalanceRow(
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("reserved_quantity")),
                fixture.balanceId());
        assertThat(row.quantity()).isEqualByComparingTo(quantity);
        assertThat(row.reserved()).isEqualByComparingTo(reserved);
    }

    private long count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    private String text(String table, String selected, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT " + selected + " FROM " + table + " WHERE " + column + " = ?",
                String.class, value);
    }

    @FunctionalInterface
    private interface ThrowingCall { void run(); }

    private record BalanceRow(BigDecimal quantity, BigDecimal reserved) {}

    private record MovementRow(
            UUID tenantId, UUID branchId, UUID productId, String type, BigDecimal quantity,
            BigDecimal quantityBefore, BigDecimal quantityAfter, UUID fromLocationId,
            UUID toLocationId, String referenceType, UUID referenceId, UUID performedByUserId) {}
}
