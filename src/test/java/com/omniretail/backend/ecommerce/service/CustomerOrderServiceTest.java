package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.dto.CustomerOrderDetailResponse;
import com.omniretail.backend.ecommerce.dto.CustomerOrderResponse;
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
class CustomerOrderServiceTest {

    @Autowired private CustomerOrderService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;

    @Test
    void listsOnlyTheAuthenticatedCustomersEcommerceOrders() {
        Fixture fixture = fixture();
        UUID otherCustomerId = customer(fixture, null, "OTRO");
        UUID ownOrder = order(fixture, fixture.customerId(), "WEB-OWN", "picking", "ecommerce");
        orderItem(fixture, ownOrder, "SKU-1");
        orderItem(fixture, ownOrder, "SKU-2");
        order(fixture, fixture.customerId(), "POS-OWN", "delivered", "pos");
        order(fixture, otherCustomerId, "WEB-OTHER", "confirmed", "ecommerce");
        actor(fixture);

        PageResponse<CustomerOrderResponse> response = service.list(PageRequest.of(0, 20));

        assertThat(response.totalItems()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(order -> {
            assertThat(order.id()).isEqualTo(ownOrder);
            assertThat(order.orderNumber()).isEqualTo("WEB-OWN");
            assertThat(order.status()).isEqualTo("preparing");
            assertThat(order.itemCount()).isEqualTo(2);
        });
    }

    @Test
    void getsOrderDetailForAuthenticatedCustomer() {
        Fixture fixture = fixture();
        UUID ownOrder = order(fixture, fixture.customerId(), "WEB-DETAIL", "picking", "ecommerce");
        orderItem(fixture, ownOrder, "SKU-DETAIL");
        actor(fixture);

        CustomerOrderDetailResponse response = service.getById(ownOrder);

        assertThat(response.orderNumber()).isEqualTo("WEB-DETAIL");
        assertThat(response.status()).isEqualTo("preparing");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("SKU-DETAIL");
            assertThat(item.quantity()).isEqualByComparingTo("1.000");
        });
    }

    @Test
    void rejectsOrderDetailWhenOrderBelongsToAnotherCustomer() {
        Fixture fixture = fixture();
        UUID otherCustomerId = customer(fixture, null, "OTRO-DETAIL");
        UUID otherOrder = order(fixture, otherCustomerId, "WEB-OTHER", "confirmed", "ecommerce");
        actor(fixture);

        assertThatThrownBy(() -> service.getById(otherOrder))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_FOUND"));
    }

    @Test
    void rejectsEmployeeSessionsEvenWhenTheyBelongToTheSameTenant() {
        Fixture fixture = fixture();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, UUID.randomUUID(), null, UUID.randomUUID()));

        assertThatThrownBy(() -> service.list(PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CUSTOMER_ACCOUNT_REQUIRED"));
    }

    private void actor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.customer, null, null, UUID.randomUUID()));
    }

    private Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """, tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """, branchId, tenantId, "BR-" + suffix.substring(0, 8), "Sucursal " + suffix);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status)
                VALUES (?, ?, ?, ?, 'customer', 'active')
                """, userId, tenantId, "Cliente " + suffix, "cliente-" + suffix + "@example.com");
        UUID customerId = customer(new Fixture(tenantId, branchId, userId, null), userId, "CLIENTE");
        return new Fixture(tenantId, branchId, userId, customerId);
    }

    private UUID customer(Fixture fixture, UUID userId, String code) {
        UUID customerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers (id, tenant_id, user_id, code, name, email, status)
                VALUES (?, ?, ?, ?, ?, ?, 'active')
                """, customerId, fixture.tenantId(), userId, code + "-" + customerId.toString().substring(0, 8),
                "Cliente " + code, code.toLowerCase() + "-" + customerId + "@example.com");
        return customerId;
    }

    private UUID order(Fixture fixture, UUID customerId, String number, String status, String source) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, customer_id, status,
                    delivery_method, transport_mode, subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'home_delivery', 'own_fleet',
                    10.00, 0.00, 0.00, 10.00, ?)
                """, id, fixture.tenantId(), fixture.branchId(), number, source, customerId, status,
                UUID.randomUUID().toString());
        return id;
    }

    private void orderItem(Fixture fixture, UUID orderId, String sku) {
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, fixture.tenantId(), "Categoría " + suffix, "categoria-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')
                """, unitId, fixture.tenantId(), "U-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, fixture.tenantId(), sku + "-" + suffix.substring(0, 8), "Producto " + sku,
                categoryId, unitId);
        jdbc.update("""
                INSERT INTO order_items (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                    inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, ?, ?, 1.000, 1.000, 10.00, 0.00, 10.00)
                """, UUID.randomUUID(), orderId, productId, sku, "Producto " + sku);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID userId, UUID customerId) {}
}
