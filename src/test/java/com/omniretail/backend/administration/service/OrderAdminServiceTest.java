package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.shared.dto.PageResponse;
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
        reservation(fixture, orderId, itemId, balanceId, "5.000", "2.000");
        actor(fixture.tenantId());

        OrderAdminResponse response = service.updateStatus(orderId, OrderStatus.cancelled);

        assertThat(response.status()).isEqualTo(OrderStatus.cancelled);
        assertThat(reservedQuantity(balanceId)).isEqualByComparingTo("2.000");
        assertThat(reservationStatus(orderId)).isEqualTo("released");
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
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, 10.000, ?)
                """, id, fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal(reservedQuantity));
        return id;
    }

    private void reservation(Fixture fixture, UUID orderId, UUID itemId, UUID balanceId,
            String reserved, String consumed) {
        String allocations = "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + balanceId
                + "\",\"locationId\":null,\"reservedQuantity\":" + reserved
                + ",\"consumedQuantity\":" + consumed + "}]";
        jdbc.update("""
                INSERT INTO inventory_reservations (id, tenant_id, branch_id, order_id, order_item_id, product_id, status, allocations)
                VALUES (?, ?, ?, ?, ?, ?, 'active', ?::jsonb)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), orderId, itemId,
                fixture.productId(), allocations);
    }

    private BigDecimal reservedQuantity(UUID balanceId) {
        return jdbc.queryForObject("SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, balanceId);
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE order_id = ?",
                String.class, orderId);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId) {}
}
