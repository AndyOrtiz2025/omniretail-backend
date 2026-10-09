package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.service.OrderEmailNotifier;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderAdminServiceTest {

    @Autowired private OrderAdminService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private OrderEmailNotifier orderEmailNotifier;

    @Test
    void listIsPaginatedAndNeverReturnsOrdersFromAnotherTenant() {
        Fixture tenantA = fixture(false);
        Fixture tenantB = fixture(false);
        order(tenantA, "WEB-A-1", "confirmed");
        order(tenantA, "WEB-A-2", "confirmed");
        order(tenantB, "WEB-B-1", "confirmed");
        actor(tenantA.tenantId());

        PageResponse<OrderAdminResponse> response = service.list(null, PageRequest.of(0, 1));

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.pageSize()).isEqualTo(1);
        assertThat(response.totalItems()).isEqualTo(2);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().orderNumber()).startsWith("WEB-A-");
    }

    @Test
    void listIncludesTheAssociatedCustomerName() {
        Fixture fixture = fixture(false);
        UUID customerId = customer(fixture, "Ana Cliente");
        order(fixture, "WEB-CUSTOMER", "confirmed", customerId);
        actor(fixture.tenantId());

        PageResponse<OrderAdminResponse> response = service.list(null, PageRequest.of(0, 20));

        assertThat(response.items()).singleElement().satisfies(order -> {
            assertThat(order.customerId()).isEqualTo(customerId);
            assertThat(order.customerName()).isEqualTo("Ana Cliente");
            assertThat(order.guestCustomer()).isNull();
        });
    }

    @Test
    void cancellationReleasesOnlyTheUnconsumedReservedStock() {
        Fixture fixture = fixture(true);
        UUID orderId = order(fixture, "WEB-CANCEL", "confirmed");
        UUID itemId = orderItem(orderId, fixture.productId());
        UUID balanceId = balance(fixture, "5.000");
        reservation(fixture, orderId, itemId, balanceId, "5.000", "2.000", "active");
        actor(fixture.tenantId());

        OrderAdminResponse response = service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(response.status()).isEqualTo(OrderStatus.cancelled);
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("2.000");
        assertThat(physicalQuantity(balanceId)).isEqualByComparingTo("10.000");
        assertThat(reservationStatus(orderId)).isEqualTo("released");
        verify(orderEmailNotifier).orderCancelled(any(Order.class));
    }

    @Test
    void cancellationCancelsPendingPicking() {
        Fixture fixture = fixture(false);
        UUID orderId = order(fixture, "WEB-PENDING-PICK", "confirmed");
        UUID pickingId = picking(fixture, orderId, "pending", null);
        actor(fixture.tenantId());

        OrderAdminResponse response = service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(response.status()).isEqualTo(OrderStatus.cancelled);
        assertThat(pickingStatus(pickingId)).isEqualTo("cancelled");
        assertThat(pickingAssignee(pickingId)).isNull();
    }

    @Test
    void cancellationCancelsAssignedAndInProgressPickingsAndClearsAssignment() {
        for (String pickingStatus : new String[] {"assigned", "in_progress"}) {
            Fixture fixture = fixture(false);
            UUID orderId = order(fixture, "WEB-" + pickingStatus, "picking");
            UUID pickingId = picking(fixture, orderId, pickingStatus, user(fixture));
            actor(fixture.tenantId());

            service.updateStatus(orderId, OrderStatus.cancelled);

            assertThat(pickingStatus(pickingId)).isEqualTo("cancelled");
            assertThat(pickingAssignee(pickingId)).isNull();
        }
    }

    @Test
    void cancellationReleasesPhysicalTraceSelectionsAndKeepsTheirHistory() {
        Fixture fixture = fixture(true);
        UUID orderId = order(fixture, "WEB-TRACE-CANCEL", "picking");
        UUID orderItemId = orderItem(orderId, fixture.productId());
        UUID balanceId = balance(fixture, "5.000");
        reservation(fixture, orderId, orderItemId, balanceId, "5.000", "active");
        UUID userId = user(fixture);
        UUID pickingId = picking(fixture, orderId, "in_progress", userId);
        UUID locationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID firstSerialId = UUID.randomUUID();
        UUID secondSerialId = UUID.randomUUID();
        UUID pickingItemId = UUID.randomUUID();
        jdbc.update(
                "UPDATE products SET tracking_lot = true, tracking_serial = true WHERE id = ?",
                fixture.productId());
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'CANCEL', 'Cancelacion', 'warehouse', 'active')
                """, locationId, fixture.tenantId(), fixture.branchId());
        jdbc.update("""
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, 'LOT-CANCEL')
                """, lotId, fixture.tenantId(), fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 10.000, 2.000)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId, lotId);
        jdbc.update("""
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number,
                     lot_id, status, version)
                VALUES
                    (?, ?, ?, ?, ?, 'SER-CANCEL-A', ?, 'RESERVED', 0),
                    (?, ?, ?, ?, ?, 'SER-CANCEL-B', ?, 'RESERVED', 0)
                """,
                firstSerialId,
                fixture.tenantId(),
                fixture.branchId(),
                locationId,
                fixture.productId(),
                lotId,
                secondSerialId,
                fixture.tenantId(),
                fixture.branchId(),
                locationId,
                fixture.productId(),
                lotId);
        String pickedTraces = """
                [{"locationId":"%s","lotId":"%s","quantity":2.000,
                  "serialNumbers":["SER-CANCEL-B","SER-CANCEL-A"]}]
                """.formatted(locationId, lotId);
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id, product_id,
                     requested_quantity, picked_quantity, location_id, status, picked_traces)
                VALUES (?, ?, ?, ?, ?, ?, 5.000, 2.000, ?, 'partial', ?::jsonb)
                """, pickingItemId, fixture.tenantId(), pickingId, orderItemId, orderItemId,
                fixture.productId(), locationId, pickedTraces);
        actor(fixture.tenantId());

        service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(jdbc.queryForObject(
                        "SELECT reserved_quantity FROM inventory_lot_balances WHERE lot_id = ?",
                        BigDecimal.class,
                        lotId))
                .isEqualByComparingTo("0.000");
        assertThat(jdbc.queryForList(
                        "SELECT status FROM inventory_serials WHERE id IN (?, ?) ORDER BY serial_number",
                        String.class,
                        firstSerialId,
                        secondSerialId))
                .containsExactly("AVAILABLE", "AVAILABLE");
        assertThat(jdbc.queryForObject(
                        "SELECT picked_traces IS NOT NULL FROM picking_items WHERE id = ?",
                        Boolean.class,
                        pickingItemId))
                .isTrue();
        assertThat(reservationStatus(orderId)).isEqualTo("released");
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("0.000");
        assertThat(pickingStatus(pickingId)).isEqualTo("cancelled");
    }

    @Test
    void repeatedCancellationPreservesAnAlreadyCancelledPickingWithoutDuplicateNotification() {
        Fixture fixture = fixture(false);
        UUID orderId = order(fixture, "WEB-CANCELLED-PICK", "cancelled");
        UUID pickingId = picking(fixture, orderId, "cancelled", null);
        actor(fixture.tenantId());

        service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(pickingStatus(pickingId)).isEqualTo("cancelled");
        verify(orderEmailNotifier, never()).orderCancelled(any(Order.class));
    }

    @Test
    void activeOrderCancellationDoesNotModifyAnAlreadyCancelledPicking() {
        Fixture fixture = fixture(false);
        UUID orderId = order(fixture, "WEB-PREVIOUSLY-CANCELLED-PICK", "confirmed");
        UUID assignedUserId = user(fixture);
        UUID pickingId = picking(fixture, orderId, "cancelled", assignedUserId);
        jdbc.update(
                "UPDATE picking_orders SET updated_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id = ?",
                pickingId);
        Instant updatedAt = pickingUpdatedAt(pickingId);
        actor(fixture.tenantId());

        OrderAdminResponse response = service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(response.status()).isEqualTo(OrderStatus.cancelled);
        assertThat(pickingStatus(pickingId)).isEqualTo("cancelled");
        assertThat(pickingAssignee(pickingId)).isEqualTo(assignedUserId);
        assertThat(pickingUpdatedAt(pickingId)).isEqualTo(updatedAt);
    }

    @Test
    void completedPickingRejectsCancellationBeforeReleasingReservations() {
        Fixture fixture = fixture(true);
        UUID orderId = order(fixture, "WEB-COMPLETED-PICK", "picking");
        UUID itemId = orderItem(orderId, fixture.productId());
        UUID balanceId = balance(fixture, "0.000", "5.000");
        reservation(fixture, orderId, itemId, balanceId, "5.000", "consumed");
        picking(fixture, orderId, "completed", null);
        actor(fixture.tenantId());

        assertThatThrownBy(() -> service.updateStatus(orderId, OrderStatus.cancelled))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVALID_ORDER_STATUS_TRANSITION");
        assertThat(orderStatus(orderId)).isEqualTo("picking");
        assertThat(reservationStatus(orderId)).isEqualTo("consumed");
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("0.000");
        assertThat(physicalQuantity(balanceId)).isEqualByComparingTo("5.000");
        verify(orderEmailNotifier, never()).orderCancelled(any(Order.class));
    }

    @Test
    void existingPackingRejectsCancellationWithoutChangingFulfillmentRecords() {
        Fixture fixture = fixture(false);
        UUID orderId = order(fixture, "WEB-EXISTING-PACKING", "picking");
        UUID userId = user(fixture);
        UUID pickingId = picking(fixture, orderId, "in_progress", userId);
        UUID packingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO packings
                    (id, tenant_id, branch_id, source_type, source_id, order_id,
                     picking_order_id, status, started_by_user_id, started_at, version)
                VALUES (?, ?, ?, 'order', ?, ?, ?, 'in_progress', ?, CURRENT_TIMESTAMP, 0)
                """, packingId, fixture.tenantId(), fixture.branchId(), orderId, orderId,
                pickingId, userId);
        actor(fixture.tenantId());

        assertThatThrownBy(() -> service.updateStatus(orderId, OrderStatus.cancelled))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVALID_ORDER_STATUS_TRANSITION");
        assertThat(orderStatus(orderId)).isEqualTo("picking");
        assertThat(pickingStatus(pickingId)).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM packings WHERE id = ?", String.class, packingId))
                .isEqualTo("in_progress");
    }

    @Test
    void fulfillmentStatesAtOrAfterPackingRejectAdministrativeCancellation() {
        for (String status : new String[] {
            "packing", "ready_for_pickup", "ready_for_dispatch", "dispatched", "delivered"
        }) {
            Fixture fixture = fixture(false);
            UUID orderId = order(fixture, "WEB-" + status, status);
            actor(fixture.tenantId());

            assertThatThrownBy(() -> service.updateStatus(orderId, OrderStatus.cancelled))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getCode())
                    .isEqualTo("INVALID_ORDER_STATUS_TRANSITION");
            assertThat(orderStatus(orderId)).isEqualTo(status);
        }
    }

    @Test
    void confirmingAPendingOrderDoesNotSendACancellationEmail() {
        Fixture fixture = fixture(false);
        UUID orderId = order(fixture, "WEB-CONFIRM", "pending");
        actor(fixture.tenantId());

        OrderAdminResponse response = service.updateStatus(orderId, OrderStatus.confirmed);

        assertThat(response.status()).isEqualTo(OrderStatus.confirmed);
        verify(orderEmailNotifier, never()).orderCancelled(any(Order.class));
    }

    @Test
    void cancellingAnAlreadyCancelledOrderDoesNotSendTheEmailAgain() {
        Fixture fixture = fixture(false);
        UUID orderId = order(fixture, "WEB-ALREADY", "cancelled");
        actor(fixture.tenantId());

        service.updateStatus(orderId, OrderStatus.cancelled);

        verify(orderEmailNotifier, never()).orderCancelled(any(Order.class));
    }

    @Test
    void cancellationDoesNotReleaseTerminalReservationAgain() {
        Fixture fixture = fixture(true);
        UUID orderId = order(fixture, "WEB-TERMINAL", "confirmed");
        UUID itemId = orderItem(orderId, fixture.productId());
        UUID balanceId = balance(fixture, "0.000", "5.000");
        reservation(fixture, orderId, itemId, balanceId, "5.000", "consumed");
        actor(fixture.tenantId());

        OrderAdminResponse response = service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(response.status()).isEqualTo(OrderStatus.cancelled);
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("0.000");
        assertThat(physicalQuantity(balanceId)).isEqualByComparingTo("5.000");
        assertThat(reservationStatus(orderId)).isEqualTo("consumed");
    }

    @Test
    void failedReservationReleaseRollsBackCancellation() {
        Fixture fixture = fixture(true);
        UUID orderId = order(fixture, "WEB-ROLLBACK", "confirmed");
        UUID itemId = orderItem(orderId, fixture.productId());
        UUID secondItemId = orderItem(orderId, fixture.productId());
        UUID balanceId = balance(fixture, "6.000");
        reservation(fixture, orderId, itemId, balanceId, "5.000", "active");
        reservation(fixture, orderId, secondItemId, balanceId, "5.000", "active");
        UUID pickingId = picking(fixture, orderId, "pending", null);
        actor(fixture.tenantId());

        assertThatThrownBy(() -> service.updateStatus(orderId, OrderStatus.cancelled))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVENTORY_RESERVATION_INCONSISTENT");
        assertThat(orderStatus(orderId)).isEqualTo("confirmed");
        verify(orderEmailNotifier, never()).orderCancelled(any(Order.class));
        assertThat(activeReservationCount(orderId)).isEqualTo(2);
        assertThat(pickingStatus(pickingId)).isEqualTo("pending");
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("6.000");
        assertThat(physicalQuantity(balanceId)).isEqualByComparingTo("10.000");
    }

    @Test
    void anotherTenantCannotCancelAnOrderOrReleaseItsReservation() {
        Fixture owner = fixture(true);
        Fixture other = fixture(false);
        UUID orderId = order(owner, "WEB-ISOLATED", "confirmed");
        UUID itemId = orderItem(orderId, owner.productId());
        UUID balanceId = balance(owner, "5.000");
        reservation(owner, orderId, itemId, balanceId, "5.000", "active");
        actor(other.tenantId());

        assertThatThrownBy(() -> service.updateStatus(orderId, OrderStatus.cancelled))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("ORDER_NOT_FOUND");
        assertThat(orderStatus(orderId)).isEqualTo("confirmed");
        assertThat(reservationStatus(orderId)).isEqualTo("active");
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("5.000");
    }

    private void actor(UUID tenantId) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), tenantId, UserType.employee, UUID.randomUUID(), null, UUID.randomUUID()));
    }

    private Fixture fixture(boolean withProduct) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """, tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """, branchId, tenantId, "BR-" + suffix.substring(0, 8), "Sucursal " + suffix);
        return new Fixture(tenantId, branchId, withProduct ? product(tenantId, suffix) : null);
    }

    private UUID product(UUID tenantId, String suffix) {
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoría " + suffix, "categoria-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')
                """, unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        return productId;
    }

    private UUID order(Fixture fixture, String number, String status) {
        return order(fixture, number, status, null);
    }

    private UUID order(Fixture fixture, String number, String status, UUID customerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, customer_id, guest_customer, status,
                    delivery_method, transport_mode, subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', ?, ?::jsonb, ?, 'store_pickup', 'none',
                    10.00, 0.00, 0.00, 10.00, ?)
                """, id, fixture.tenantId(), fixture.branchId(), number, customerId, customerId == null ? "{}" : null, status,
                UUID.randomUUID().toString());
        return id;
    }

    private UUID customer(Fixture fixture, String name) {
        UUID id = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, tenant_id, code, name, email, status)
                VALUES (?, ?, ?, ?, ?, 'active')
                """, id, fixture.tenantId(), "C-" + suffix.substring(0, 8), name,
                "customer-" + suffix + "@example.com");
        return id;
    }

    private UUID user(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Picker', ?, 'employee', 'active', ?)
                """, id, fixture.tenantId(), id + "@test.local", fixture.branchId());
        return id;
    }

    private UUID picking(Fixture fixture, UUID orderId, String status, UUID assignedUserId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, order_id,
                     assigned_user_id, status, priority, completed_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, 'normal',
                        CASE WHEN ? = 'completed' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, id, fixture.tenantId(), fixture.branchId(), orderId, orderId,
                assignedUserId, status, status);
        return id;
    }

    private UUID orderItem(UUID orderId, UUID productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_items (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                    inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, 'SKU', 'Producto', 5.000, 5.000, 10.00, 0.00, 10.00)
                """, id, orderId, productId);
        return id;
    }

    private UUID balance(Fixture fixture, String reservedQuantity) {
        return balance(fixture, reservedQuantity, "10.000");
    }

    private UUID balance(Fixture fixture, String reservedQuantity, String physicalQuantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, fixture.tenantId(), fixture.branchId(), fixture.productId(),
                new BigDecimal(physicalQuantity), new BigDecimal(reservedQuantity));
        return id;
    }

    private void reservation(Fixture fixture, UUID orderId, UUID itemId, UUID balanceId,
            String reserved, String status) {
        reservation(fixture, orderId, itemId, balanceId, reserved,
                "consumed".equals(status) ? reserved : "0", status);
    }

    private void reservation(Fixture fixture, UUID orderId, UUID itemId, UUID balanceId,
            String reserved, String consumed, String status) {
        String allocations = "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + balanceId
                + "\",\"locationId\":null,\"reservedQuantity\":" + reserved
                + ",\"consumedQuantity\":" + consumed + "}]";
        jdbc.update("""
                INSERT INTO inventory_reservations (id, tenant_id, branch_id, source_type, source_id, source_line_id,
                    order_id, order_item_id, product_id, quantity, status, allocations)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), orderId, itemId,
                orderId, itemId, fixture.productId(), new BigDecimal(reserved), status, allocations);
    }

    private BigDecimal reservedQuantity(UUID balanceId) {
        return jdbc.queryForObject("SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, balanceId);
    }

    private BigDecimal physicalQuantity(UUID balanceId) {
        return jdbc.queryForObject("SELECT quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, balanceId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String pickingStatus(UUID pickingId) {
        return jdbc.queryForObject(
                "SELECT status FROM picking_orders WHERE id = ?", String.class, pickingId);
    }

    private UUID pickingAssignee(UUID pickingId) {
        return jdbc.queryForObject(
                "SELECT assigned_user_id FROM picking_orders WHERE id = ?", UUID.class, pickingId);
    }

    private Instant pickingUpdatedAt(UUID pickingId) {
        return jdbc.queryForObject(
                "SELECT updated_at FROM picking_orders WHERE id = ?", Instant.class, pickingId);
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE order_id = ?",
                String.class, orderId);
    }

    private Integer activeReservationCount(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_reservations WHERE order_id = ? AND status = 'active'",
                Integer.class, orderId);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId) {}
}
