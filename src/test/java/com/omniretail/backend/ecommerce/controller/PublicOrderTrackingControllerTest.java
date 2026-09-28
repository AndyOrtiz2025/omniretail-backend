package com.omniretail.backend.ecommerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** En perfil test no se carga la semilla: cada test crea su tienda, configuracion y pedido. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicOrderTrackingControllerTest {

    private static final String GUEST_NAME = "Cliente Invitado Secreto";
    private static final String GUEST_EMAIL = "invitado.secreto@example.com";
    private static final String ADDRESS_LINE = "Calle Privada 123";
    private static final String NOT_FOUND_MESSAGE = "No encontramos un pedido con ese código de seguimiento.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EcommerceConfigRepository ecommerceConfigRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Test
    void anonymousRequestReturnsOrderWithoutPrivateData() throws Exception {
        Store store = store(true, true);
        Order order = order(store, OrderSource.ecommerce, OrderStatus.confirmed);

        String body = track(store.slug(), order.getTrackingToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(order.getOrderNumber()))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.total").value(35.50))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.sku == 'MART-01')].name").value("Martillo"))
                .andExpect(jsonPath("$.items[?(@.sku == 'MART-01')].quantity").value(2.0))
                .andExpect(jsonPath("$.items[?(@.sku == 'MART-01')].subtotal").value(30.0))
                .andExpect(jsonPath("$.items[?(@.sku == 'CLAV-01')].name").value("Clavos"))
                .andExpect(jsonPath("$.items[?(@.sku == 'CLAV-01')].subtotal").value(5.5))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(GUEST_NAME, GUEST_EMAIL, ADDRESS_LINE, store.tenantId().toString(),
                order.getTrackingToken(), order.getId().toString(), "tenantId", "trackingToken", "email");
    }

    @Test
    void internalStatusesAreMappedToCustomerStatuses() throws Exception {
        Store store = store(true, true);

        track(store.slug(), order(store, OrderSource.ecommerce, OrderStatus.picking).getTrackingToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("preparing"));
        track(store.slug(), order(store, OrderSource.ecommerce, OrderStatus.dispatched).getTrackingToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));
    }

    @Test
    void unknownTokenReturnsGenericNotFound() throws Exception {
        Store store = store(true, true);

        assertGenericNotFound(track(store.slug(), UUID.randomUUID().toString()));
    }

    @Test
    void unknownSlugReturnsGenericNotFound() throws Exception {
        Store store = store(true, true);
        Order order = order(store, OrderSource.ecommerce, OrderStatus.confirmed);

        assertGenericNotFound(track("tienda-que-no-existe-" + UUID.randomUUID(), order.getTrackingToken()));
    }

    @Test
    void orderFromAnotherStoreReturnsGenericNotFound() throws Exception {
        Store storeA = store(true, true);
        Store storeB = store(true, true);
        Order orderOfB = order(storeB, OrderSource.ecommerce, OrderStatus.confirmed);

        assertGenericNotFound(track(storeA.slug(), orderOfB.getTrackingToken()));
    }

    @Test
    void posOrderReturnsGenericNotFound() throws Exception {
        Store store = store(true, true);
        Order posOrder = order(store, OrderSource.pos, OrderStatus.delivered);

        assertGenericNotFound(track(store.slug(), posOrder.getTrackingToken()));
    }

    @Test
    void guestTrackingDisabledReturnsGenericNotFound() throws Exception {
        Store store = store(true, false);
        Order order = order(store, OrderSource.ecommerce, OrderStatus.confirmed);

        assertGenericNotFound(track(store.slug(), order.getTrackingToken()));
    }

    @Test
    void ecommerceDisabledReturnsGenericNotFound() throws Exception {
        Store store = store(false, true);
        Order order = order(store, OrderSource.ecommerce, OrderStatus.confirmed);

        assertGenericNotFound(track(store.slug(), order.getTrackingToken()));
    }

    @Test
    void storeWithoutEcommerceConfigReturnsGenericNotFound() throws Exception {
        Store store = storeWithoutConfig("active");
        Order order = order(store, OrderSource.ecommerce, OrderStatus.confirmed);

        assertGenericNotFound(track(store.slug(), order.getTrackingToken()));
    }

    @Test
    void inactiveStoreReturnsGenericNotFound() throws Exception {
        Store store = storeWithoutConfig("inactive");
        saveConfig(store, true, true);
        Order order = order(store, OrderSource.ecommerce, OrderStatus.confirmed);

        assertGenericNotFound(track(store.slug(), order.getTrackingToken()));
    }

    private ResultActions track(String slug, String token) throws Exception {
        // Sin header Authorization: el endpoint es publico.
        return mockMvc.perform(get("/api/v1/public/" + slug + "/tracking/" + token));
    }

    private static void assertGenericNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_TRACKING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
    }

    private Store store(boolean ecommerceEnabled, boolean guestTrackingEnabled) {
        Store store = storeWithoutConfig("active");
        saveConfig(store, ecommerceEnabled, guestTrackingEnabled);
        return store;
    }

    private void saveConfig(Store store, boolean enabled, boolean guestTrackingEnabled) {
        EcommerceConfig config = EcommerceConfig.builder()
                .enabled(enabled)
                .storeName("Tienda " + store.slug())
                .guestTrackingEnabled(guestTrackingEnabled)
                .build();
        config.setTenantId(store.tenantId());
        ecommerceConfigRepository.save(config);
    }

    /** Tienda con sucursal y dos productos, insertada por SQL como en EcommercePersistenceTest. */
    private Store storeWithoutConfig(String tenantStatus) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String slug = "tienda-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, ?, 'GTQ', 'America/Guatemala')
                """, tenantId, "Tienda " + suffix, slug, tenantStatus);
        jdbcTemplate.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """, branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbcTemplate.update("""
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, ?, ?, 'active')
                """, categoryId, tenantId, "Categoría " + suffix, "categoria-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, 'UND', 'Unidad', 'und', 'unit', false, 'active')
                """, unitId, tenantId);
        UUID hammerId = product(tenantId, categoryId, unitId, "MART-01", "Martillo");
        UUID nailsId = product(tenantId, categoryId, unitId, "CLAV-01", "Clavos");
        return new Store(tenantId, branchId, slug, hammerId, nailsId);
    }

    private UUID product(UUID tenantId, UUID categoryId, UUID unitId, String sku, String name) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, tenantId, sku, name, categoryId, unitId);
        return productId;
    }

    /** Pedido con cliente invitado y direccion, para verificar que nunca se filtran en la respuesta. */
    private Order order(Store store, OrderSource source, OrderStatus status) {
        Order order = Order.builder()
                .branchId(store.branchId())
                .orderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8))
                .source(source)
                .guestCustomer("{\"name\": \"" + GUEST_NAME + "\", \"email\": \"" + GUEST_EMAIL + "\"}")
                .status(status)
                .deliveryMethod(DeliveryMethod.home_delivery)
                .transportMode(TransportMode.own_fleet)
                .deliveryAddress("{\"line1\": \"" + ADDRESS_LINE + "\"}")
                .subtotal(new BigDecimal("35.50"))
                .discountTotal(BigDecimal.ZERO)
                .shippingTotal(BigDecimal.ZERO)
                .total(new BigDecimal("35.50"))
                .trackingToken(UUID.randomUUID().toString())
                .build();
        order.setTenantId(store.tenantId());
        order = orderRepository.save(order);
        orderItemRepository.save(item(order, store.hammerId(), "MART-01", "Martillo", "2", "15.00", "30.00"));
        orderItemRepository.save(item(order, store.nailsId(), "CLAV-01", "Clavos", "1", "5.50", "5.50"));
        return order;
    }

    private static OrderItem item(Order order, UUID productId, String sku, String name, String quantity,
            String unitPrice, String subtotal) {
        return OrderItem.builder()
                .orderId(order.getId())
                .productId(productId)
                .skuSnapshot(sku)
                .nameSnapshot(name)
                .quantity(new BigDecimal(quantity))
                .inventoryQuantity(new BigDecimal(quantity))
                .unitPrice(new BigDecimal(unitPrice))
                .discount(BigDecimal.ZERO)
                .subtotal(new BigDecimal(subtotal))
                .fulfillmentComponents("[]")
                .build();
    }

    private record Store(UUID tenantId, UUID branchId, String slug, UUID hammerId, UUID nailsId) {}
}
