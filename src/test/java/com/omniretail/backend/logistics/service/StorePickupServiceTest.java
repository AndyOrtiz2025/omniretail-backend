package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.logistics.dto.StorePickupHandoverResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
class StorePickupServiceTest {

    @Autowired private StorePickupService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowBranch() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void handoverConsumesInventoryOnceAndRetryReturnsPersistedResult() {
        DispatchTestFixture.Data fixture = pickupFixture();
        actor(fixture);

        StorePickupHandoverResponse first =
                service.handover(fixture.branchId(), fixture.orderId());
        StorePickupHandoverResponse retry =
                service.handover(fixture.branchId(), fixture.orderId());

        assertThat(first.status().name()).isEqualTo("delivered");
        assertThat(first.deliveredAt()).isNotNull();
        assertThat(first.idempotent()).isFalse();
        assertThat(retry.deliveredAt()).isEqualTo(first.deliveredAt());
        assertThat(retry.idempotent()).isTrue();
        assertThat(text("orders", "status", fixture.orderId())).isEqualTo("delivered");
        assertThat(jdbc.queryForObject(
                "SELECT delivered_at FROM orders WHERE id = ?", Object.class, fixture.orderId()))
                .isNotNull();
        assertThat(text("inventory_reservations", "status", fixture.reservationId()))
                .isEqualTo("consumed");
        assertThat(decimal("inventory_balances", "quantity", fixture.balanceId()))
                .isEqualByComparingTo("5.000");
        assertThat(decimal("inventory_balances", "reserved_quantity", fixture.balanceId()))
                .isEqualByComparingTo("0.000");
        assertThat(count("inventory_movements", "reference_id", fixture.orderId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT reference_type FROM inventory_movements WHERE reference_id = ?",
                String.class,
                fixture.orderId())).isEqualTo("order");
        assertThat(jdbc.queryForObject(
                "SELECT type FROM inventory_movements WHERE reference_id = ?",
                String.class,
                fixture.orderId())).isEqualTo("out");
        assertThat(count("dispatches", "order_id", fixture.orderId())).isZero();
    }

    @Test
    void traceableHandoverConsumesPickedLotAndSerialsExactlyOnce() {
        DispatchTestFixture.Data fixture = pickupFixture();
        actor(fixture);
        UUID lot = UUID.randomUUID();
        jdbc.update(
                "UPDATE products SET tracking_lot = true, tracking_serial = true WHERE id = ?",
                fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, 'PICKUP-LOT')
                """, lot, fixture.tenantId(), fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 5.000, 5.000)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(),
                fixture.locationId(), lot);
        for (int index = 1; index <= 5; index++) {
            jdbc.update("""
                    INSERT INTO inventory_serials
                        (id, tenant_id, branch_id, location_id, product_id, serial_number,
                         lot_id, status, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', 0)
                    """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(),
                    fixture.locationId(), fixture.productId(), "PICKUP-SER-" + index, lot);
        }
        UUID pickingId = jdbc.queryForObject(
                "SELECT picking_order_id FROM packings WHERE id = ?",
                UUID.class,
                fixture.packingId());
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id,
                     product_id, requested_quantity, picked_quantity, location_id,
                     picked_traces, status)
                VALUES (?, ?, ?, ?, ?, ?, 5.000, 5.000, ?, ?::jsonb, 'completed')
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                pickingId,
                fixture.orderItemId(),
                fixture.orderItemId(),
                fixture.productId(),
                fixture.locationId(),
                """
                [{"locationId":"%s","lotId":"%s","quantity":5.000,
                  "serialNumbers":["PICKUP-SER-1","PICKUP-SER-2","PICKUP-SER-3",
                                   "PICKUP-SER-4","PICKUP-SER-5"]}]
                """.formatted(fixture.locationId(), lot));

        service.handover(fixture.branchId(), fixture.orderId());
        StorePickupHandoverResponse retry =
                service.handover(fixture.branchId(), fixture.orderId());

        assertThat(retry.idempotent()).isTrue();
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
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_serials WHERE lot_id = ? AND status = 'CONSUMED'",
                        Long.class,
                        lot))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM inventory_movement_traces trace
                        JOIN inventory_movements movement ON movement.id = trace.movement_id
                        WHERE movement.reference_id = ?
                          AND movement.reference_line_id = ?
                          AND trace.serial_id IS NOT NULL
                        """,
                        Long.class,
                        fixture.orderId(),
                        fixture.orderItemId()))
                .isEqualTo(5L);
        assertThat(count("inventory_movements", "reference_id", fixture.orderId())).isOne();
    }

    @Test
    void concurrentHandoversConsumeInventoryAndCreateMovementOnce() {
        DispatchTestFixture.Data fixture = pickupFixture();
        actor(fixture);

        CompletableFuture<StorePickupHandoverResponse> first = CompletableFuture.supplyAsync(
                () -> service.handover(fixture.branchId(), fixture.orderId()));
        CompletableFuture<StorePickupHandoverResponse> second = CompletableFuture.supplyAsync(
                () -> service.handover(fixture.branchId(), fixture.orderId()));

        assertThat(java.util.List.of(first.join().idempotent(), second.join().idempotent()))
                .containsExactlyInAnyOrder(false, true);
        assertThat(count("inventory_movements", "reference_id", fixture.orderId())).isOne();
        assertThat(decimal("inventory_balances", "quantity", fixture.balanceId()))
                .isEqualByComparingTo("5.000");
    }

    @Test
    void rejectsWrongBranchSourceDeliveryMethodAndState() {
        DispatchTestFixture.Data fixture = pickupFixture();
        actor(fixture);

        assertCode(
                () -> service.handover(UUID.randomUUID(), fixture.orderId()),
                "ORDER_NOT_FOUND");

        jdbc.update("UPDATE orders SET source = 'ecommerce' WHERE id = ?", fixture.orderId());
        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "STORE_PICKUP_ORDER_NOT_ELIGIBLE");
        jdbc.update(
                "UPDATE orders SET source = 'pos', delivery_method = 'home_delivery' WHERE id = ?",
                fixture.orderId());
        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "STORE_PICKUP_ORDER_NOT_ELIGIBLE");
        jdbc.update(
                "UPDATE orders SET delivery_method = 'store_pickup', status = 'packing' WHERE id = ?",
                fixture.orderId());
        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "INVALID_ORDER_STATUS_TRANSITION");
    }

    @Test
    void rejectsIncompletePickingOrPacking() {
        DispatchTestFixture.Data fixture = pickupFixture();
        actor(fixture);
        jdbc.update(
                "UPDATE picking_orders SET status = 'in_progress', completed_at = NULL WHERE source_id = ?",
                fixture.orderId());

        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "PICKING_NOT_COMPLETED");

        jdbc.update(
                "UPDATE picking_orders SET status = 'completed', completed_at = now() WHERE source_id = ?",
                fixture.orderId());
        jdbc.update(
                "UPDATE packings SET status = 'in_progress', finalized_by_user_id = NULL, "
                        + "finalized_at = NULL WHERE id = ?",
                fixture.packingId());
        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "PACKING_NOT_FINALIZED");
    }

    @Test
    void requiresAnActiveReservationBeforeHandover() {
        DispatchTestFixture.Data fixture = pickupFixture();
        actor(fixture);
        jdbc.update(
                "UPDATE inventory_reservations SET status = 'released' WHERE id = ?",
                fixture.reservationId());
        jdbc.update(
                "UPDATE inventory_balances SET reserved_quantity = 0 WHERE id = ?",
                fixture.balanceId());

        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "INVENTORY_RESERVATION_NOT_ACTIVE");

        assertThat(text("orders", "status", fixture.orderId())).isEqualTo("ready_for_pickup");
        assertThat(decimal("inventory_balances", "quantity", fixture.balanceId()))
                .isEqualByComparingTo("10.000");
        assertThat(count("inventory_movements", "reference_id", fixture.orderId())).isZero();
    }

    @Test
    void enforcesTenantAndBranchAccess() {
        DispatchTestFixture.Data fixture = pickupFixture();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), UUID.randomUUID(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "ORDER_NOT_FOUND");

        actor(fixture);
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode(
                () -> service.handover(fixture.branchId(), fixture.orderId()),
                "BRANCH_ACCESS_DENIED");
    }

    private DispatchTestFixture.Data pickupFixture() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 1);
        jdbc.update(
                "UPDATE orders SET source = 'pos', status = 'ready_for_pickup', "
                        + "delivery_method = 'store_pickup', transport_mode = 'customer' WHERE id = ?",
                fixture.orderId());
        return fixture;
    }

    private void actor(DispatchTestFixture.Data fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
    }

    private String text(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
    }

    private BigDecimal decimal(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", BigDecimal.class, id);
    }

    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                id);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(code);
    }
}
