package com.omniretail.backend.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.ecommerce.entity.Address;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EcommercePersistenceTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void savesAndReadsCustomerAddressUsingReferenceNotes() {
        Fixture fixture = createFixture(false);
        Customer customer = Customer.builder()
                .code("WEB-" + UUID.randomUUID())
                .name("María López")
                .email("maria@example.com")
                .status(CustomerStatus.active)
                .build();
        customer.setTenantId(fixture.tenantId());
        entityManager.persist(customer);
        entityManager.flush();

        Address address = Address.builder()
                .customerId(customer.getId())
                .label("Casa")
                .recipientName("María López")
                .line1("7a Avenida #3-73, Zona 1")
                .line2("Apartamento 4")
                .city("Guatemala")
                .country("Guatemala")
                .references("Portón verde, frente al parque")
                .build();
        address.setTenantId(fixture.tenantId());
        entityManager.persist(address);
        entityManager.flush();
        entityManager.clear();

        Address reloaded = entityManager.find(Address.class, address.getId());
        assertThat(reloaded.getReferences()).isEqualTo("Portón verde, frente al parque");
        assertThat(reloaded.getLine1()).contains("#3-73");
    }

    @Test
    @Transactional
    void allowsPosOrderWithoutCustomerOrGuestCustomer() {
        Fixture fixture = createFixture(false);

        Order order = persistPosOrder(fixture);

        assertThat(order.getId()).isNotNull();
    }

    @Test
    void rejectsEcommerceOrderWithoutCustomerOrGuestCustomer() {
        Fixture fixture = createFixture(false);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO orders (id, tenant_id, branch_id, order_number, source, status,
                            delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                            total, tracking_token)
                        VALUES (?, ?, ?, ?, 'ecommerce', 'pending', 'home_delivery', 'third_party',
                            1.00, 0.00, 0.00, 1.00, ?)
                        """,
                        UUID.randomUUID(),
                        fixture.tenantId(),
                        fixture.branchId(),
                        "WEB-" + UUID.randomUUID(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void savesOrderItemAndInventoryReservationForPosOrder() {
        Fixture fixture = createFixture(true);
        Order order = persistPosOrder(fixture);
        OrderItem item = OrderItem.builder()
                .orderId(order.getId())
                .productId(fixture.productId())
                .skuSnapshot("SKU-" + UUID.randomUUID())
                .nameSnapshot("Producto de prueba")
                .quantity(new BigDecimal("1.000"))
                .inventoryQuantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal("10.00"))
                .discount(BigDecimal.ZERO)
                .subtotal(new BigDecimal("10.00"))
                .fulfillmentComponents("[]")
                .build();
        entityManager.persist(item);
        entityManager.flush();

        InventoryReservation reservation = InventoryReservation.builder()
                .branchId(fixture.branchId())
                .orderId(order.getId())
                .orderItemId(item.getId())
                .productId(fixture.productId())
                .build();
        reservation.setTenantId(fixture.tenantId());
        entityManager.persist(reservation);
        entityManager.flush();

        assertThat(reservation.getId()).isNotNull();
    }

    private Order persistPosOrder(Fixture fixture) {
        Order order = Order.builder()
                .branchId(fixture.branchId())
                .orderNumber("POS-" + UUID.randomUUID())
                .source(OrderSource.pos)
                .status(OrderStatus.pending)
                .deliveryMethod(DeliveryMethod.immediate)
                .transportMode(TransportMode.none)
                .subtotal(new BigDecimal("10.00"))
                .discountTotal(BigDecimal.ZERO)
                .shippingTotal(BigDecimal.ZERO)
                .total(new BigDecimal("10.00"))
                .trackingToken(UUID.randomUUID().toString())
                .build();
        order.setTenantId(fixture.tenantId());
        entityManager.persist(order);
        entityManager.flush();
        return order;
    }

    private Fixture createFixture(boolean includeProduct) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """,
                branchId,
                tenantId,
                "MAIN-" + suffix,
                "Principal " + suffix);
        return new Fixture(tenantId, branchId, includeProduct ? createProduct(tenantId, suffix) : null);
    }

    private UUID createProduct(UUID tenantId, String suffix) {
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, ?, ?, 'active')
                """,
                categoryId,
                tenantId,
                "Categoría " + suffix,
                "categoria-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, 'UND', 'Unidad', 'und', 'unit', false, 'active')
                """,
                unitId,
                tenantId);
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
        return productId;
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId) {}
}
