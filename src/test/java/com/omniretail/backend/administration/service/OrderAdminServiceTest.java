package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
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
        actor(fixture.tenantId());

        assertThatThrownBy(() -> service.updateStatus(orderId, OrderStatus.cancelled))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVENTORY_RESERVATION_INCONSISTENT");
        assertThat(orderStatus(orderId)).isEqualTo("confirmed");
        assertThat(activeReservationCount(orderId)).isEqualTo(2);
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
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                    delivery_method, transport_mode, subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{}'::jsonb, ?, 'store_pickup', 'none',
                    10.00, 0.00, 0.00, 10.00, ?)
                """, id, fixture.tenantId(), fixture.branchId(), number, status, UUID.randomUUID().toString());
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
