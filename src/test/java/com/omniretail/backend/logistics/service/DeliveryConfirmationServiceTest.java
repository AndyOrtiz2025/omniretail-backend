package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.DeliveryConfirmationResponse;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
class DeliveryConfirmationServiceTest {

    @Autowired private DeliveryConfirmationService service;
    @Autowired private DispatchService dispatchService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowBranch() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void confirmsEcommerceDeliveryAtomicallyAndRetryKeepsTimestampAndInventory() {
        Dispatched fixture = dispatched(OrderSource.ecommerce);
        PhysicalSnapshot before = physicalSnapshot(fixture.data());

        DeliveryConfirmationResponse first =
                service.confirm(fixture.data().branchId(), fixture.data().orderId());
        DeliveryConfirmationResponse retry =
                service.confirm(fixture.data().branchId(), fixture.data().orderId());

        assertThat(first.status().name()).isEqualTo("delivered");
        assertThat(first.idempotent()).isFalse();
        assertThat(first.deliveredAt()).isNotNull();
        assertThat(retry.idempotent()).isTrue();
        assertThat(retry.deliveredAt()).isEqualTo(first.deliveredAt());
        assertThat(text("orders", "status", fixture.data().orderId())).isEqualTo("delivered");
        assertThat(text("dispatches", "status", fixture.dispatchId())).isEqualTo("delivered");
        assertThat(timestamp("orders", fixture.data().orderId()))
                .isEqualTo(timestamp("dispatches", fixture.dispatchId()));
        assertThat(physicalSnapshot(fixture.data())).isEqualTo(before);
    }

    @Test
    void confirmsPosDeferredHomeDeliveryWithoutDependingOnOrderSource() {
        Dispatched fixture = dispatched(OrderSource.pos);

        DeliveryConfirmationResponse result =
                service.confirm(fixture.data().branchId(), fixture.data().orderId());

        assertThat(result.status().name()).isEqualTo("delivered");
        assertThat(result.idempotent()).isFalse();
    }

    @Test
    void traceableDispatchedOrderAddsNoMovementOrTrace() {
        DispatchTestFixture.Data data = DispatchTestFixture.create(jdbc, 1);
        actor(data);
        UUID picking = jdbc.queryForObject(
                "SELECT picking_order_id FROM packings WHERE id = ?",
                UUID.class,
                data.packingId());
        jdbc.update("UPDATE products SET tracking_serial = true WHERE id = ?", data.productId());
        for (int index = 1; index <= 5; index++) {
            jdbc.update("""
                    INSERT INTO inventory_serials
                        (id, tenant_id, branch_id, location_id, product_id,
                         serial_number, status, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'RESERVED', 0)
                    """, UUID.randomUUID(), data.tenantId(), data.branchId(), data.locationId(),
                    data.productId(), "DELIVERY-SER-" + index);
        }
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id,
                     product_id, requested_quantity, picked_quantity, location_id,
                     picked_traces, status)
                VALUES (?, ?, ?, ?, ?, ?, 5.000, 5.000, ?, ?::jsonb, 'completed')
                """, UUID.randomUUID(), data.tenantId(), picking, data.orderItemId(),
                data.orderItemId(), data.productId(), data.locationId(),
                "[{\"locationId\":\"" + data.locationId()
                        + "\",\"lotId\":null,\"quantity\":5.000,\"serialNumbers\":["
                        + "\"DELIVERY-SER-1\",\"DELIVERY-SER-2\",\"DELIVERY-SER-3\","
                        + "\"DELIVERY-SER-4\",\"DELIVERY-SER-5\"]}]");
        DispatchResponse dispatch = dispatchService.confirm(
                data.branchId(), data.orderId(), dispatchRequest());
        long movementCount = count("inventory_movements", data.tenantId());
        long traceCount = count("inventory_movement_traces", data.tenantId());
        long consumedSerials = jdbc.queryForObject(
                "SELECT count(*) FROM inventory_serials WHERE tenant_id = ? AND status = 'CONSUMED'",
                Long.class,
                data.tenantId());

        service.confirm(data.branchId(), data.orderId());

        assertThat(dispatch.dispatchId()).isNotNull();
        assertThat(count("inventory_movements", data.tenantId())).isEqualTo(movementCount);
        assertThat(count("inventory_movement_traces", data.tenantId())).isEqualTo(traceCount);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_serials WHERE tenant_id = ? AND status = 'CONSUMED'",
                        Long.class,
                        data.tenantId()))
                .isEqualTo(consumedSerials);
    }

    @Test
    void rejectsInvalidOrderStatuses() {
        DispatchTestFixture.Data ready = DispatchTestFixture.create(jdbc, 1);
        actor(ready);
        assertCode(
                () -> service.confirm(ready.branchId(), ready.orderId()),
                "INVALID_ORDER_STATUS_TRANSITION");

        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", ready.orderId());
        assertCode(
                () -> service.confirm(ready.branchId(), ready.orderId()),
                "INVALID_ORDER_STATUS_TRANSITION");
    }

    @Test
    void rejectsStorePickupWrongBranchAndForeignTenant() {
        DispatchTestFixture.Data data = DispatchTestFixture.create(jdbc, 1);
        actor(data);
        jdbc.update("UPDATE orders SET delivery_method = 'store_pickup' WHERE id = ?", data.orderId());
        assertCode(
                () -> service.confirm(data.branchId(), data.orderId()),
                "UNSUPPORTED_FULFILLMENT");

        jdbc.update("UPDATE orders SET delivery_method = 'home_delivery' WHERE id = ?", data.orderId());
        assertCode(
                () -> service.confirm(UUID.randomUUID(), data.orderId()),
                "ORDER_NOT_FOUND");

        given(currentUser.require()).willReturn(new AuthenticatedUser(
                data.userId(), UUID.randomUUID(), UserType.employee,
                null, data.branchId(), UUID.randomUUID()));
        assertCode(
                () -> service.confirm(data.branchId(), data.orderId()),
                "ORDER_NOT_FOUND");
    }

    @Test
    void enforcesBranchAccess() {
        DispatchTestFixture.Data data = DispatchTestFixture.create(jdbc, 1);
        actor(data);
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));

        assertCode(
                () -> service.confirm(data.branchId(), data.orderId()),
                "BRANCH_ACCESS_DENIED");
    }

    @Test
    void rejectsMissingOrInconsistentDispatch() {
        DispatchTestFixture.Data missing = DispatchTestFixture.create(jdbc, 1);
        actor(missing);
        jdbc.update("UPDATE orders SET status = 'dispatched' WHERE id = ?", missing.orderId());
        assertCode(
                () -> service.confirm(missing.branchId(), missing.orderId()),
                "DISPATCH_NOT_FOUND");

        Dispatched inconsistent = dispatched(OrderSource.ecommerce);
        jdbc.update(
                "UPDATE dispatches SET status = 'delivered', delivered_at = now() WHERE id = ?",
                inconsistent.dispatchId());
        assertCode(
                () -> service.confirm(
                        inconsistent.data().branchId(), inconsistent.data().orderId()),
                "INVALID_ORDER_STATUS_TRANSITION");
    }

    @Test
    void concurrentConfirmationsProduceOneTransitionAndOneRetry() {
        Dispatched fixture = dispatched(OrderSource.ecommerce);

        CompletableFuture<DeliveryConfirmationResponse> first = CompletableFuture.supplyAsync(
                () -> service.confirm(fixture.data().branchId(), fixture.data().orderId()));
        CompletableFuture<DeliveryConfirmationResponse> second = CompletableFuture.supplyAsync(
                () -> service.confirm(fixture.data().branchId(), fixture.data().orderId()));
        DeliveryConfirmationResponse firstResult = first.join();
        DeliveryConfirmationResponse secondResult = second.join();

        assertThat(List.of(firstResult.idempotent(), secondResult.idempotent()))
                .containsExactlyInAnyOrder(false, true);
        assertThat(firstResult.deliveredAt()).isEqualTo(secondResult.deliveredAt());
    }

    private Dispatched dispatched(OrderSource source) {
        DispatchTestFixture.Data data = DispatchTestFixture.create(jdbc, 1);
        actor(data);
        jdbc.update("UPDATE orders SET source = ? WHERE id = ?", source.name(), data.orderId());
        DispatchResponse response =
                dispatchService.confirm(data.branchId(), data.orderId(), dispatchRequest());
        return new Dispatched(data, response.dispatchId());
    }

    private ConfirmDispatchRequest dispatchRequest() {
        return new ConfirmDispatchRequest(
                "delivery-setup-" + UUID.randomUUID(),
                "Transportista",
                "TRK-" + UUID.randomUUID(),
                List.of(new ConfirmDispatchRequest.PackageRequest(
                        "PKG-1", new BigDecimal("1.000"), null)));
    }

    private void actor(DispatchTestFixture.Data data) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                data.userId(), data.tenantId(), UserType.employee,
                null, data.branchId(), UUID.randomUUID()));
    }

    private PhysicalSnapshot physicalSnapshot(DispatchTestFixture.Data data) {
        return new PhysicalSnapshot(
                text("inventory_reservations", "status", data.reservationId()),
                decimal("inventory_balances", "quantity", data.balanceId()),
                decimal("inventory_balances", "reserved_quantity", data.balanceId()),
                count("inventory_movements", data.tenantId()),
                count("inventory_movement_traces", data.tenantId()));
    }

    private String text(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
    }

    private BigDecimal decimal(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", BigDecimal.class, id);
    }

    private Instant timestamp(String table, UUID id) {
        return jdbc.queryForObject(
                        "SELECT delivered_at FROM " + table + " WHERE id = ?",
                        Timestamp.class,
                        id)
                .toInstant();
    }

    private long count(String table, UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenantId);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(code);
    }

    private record Dispatched(DispatchTestFixture.Data data, UUID dispatchId) {}

    private record PhysicalSnapshot(
            String reservationStatus,
            BigDecimal quantity,
            BigDecimal reservedQuantity,
            long movements,
            long traces) {}
}
