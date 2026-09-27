package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerAdminControllerTest {

    private static final String BASE_URL = "/api/v1/administration/customers";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void listCustomersEmptyReturnsEmptyList() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listCustomersCalculatesPurchaseCountAndTopProductsAndSorts() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));
        Fixture fixture = createFixture(tenant.getId());

        UUID customerA = insertCustomer(tenant.getId(), "CUST-001", "Ana Martínez", "ana@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-002", "Bernardo López", "bernardo@example.com", "active");

        // Cliente A: 1 pedido con 2 productos (martillo x 2, clavos x 5)
        UUID orderId = insertOrder(tenant.getId(), fixture.branchId(), customerA, "ORD-1001", "confirmed");
        insertOrderItem(orderId, fixture.product1Id(), "SKU-MARTILLO", "Martillo 16 oz", new BigDecimal("2.000"));
        insertOrderItem(orderId, fixture.product2Id(), "SKU-CLAVOS", "Clavos 2 pulgadas", new BigDecimal("5.000"));

        // Cliente A: 1 venta directa con 1 producto (martillo x 1)
        UUID saleId = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customerA, null, "V-1001", "completed");
        insertSaleItem(saleId, fixture.product1Id(), "SKU-MARTILLO", "Martillo 16 oz", new BigDecimal("1.000"));

        // Cliente B: sin compras

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Cliente A va primero porque purchaseCount = 2
                .andExpect(jsonPath("$[0].code").value("CUST-001"))
                .andExpect(jsonPath("$[0].name").value("Ana Martínez"))
                .andExpect(jsonPath("$[0].purchaseCount").value(2))
                .andExpect(jsonPath("$[0].topProducts.length()").value(2))
                .andExpect(jsonPath("$[0].topProducts[0].productName").value("Clavos 2 pulgadas"))
                .andExpect(jsonPath("$[0].topProducts[0].totalQuantity").value(5.0))
                .andExpect(jsonPath("$[0].topProducts[1].productName").value("Martillo 16 oz"))
                .andExpect(jsonPath("$[0].topProducts[1].totalQuantity").value(3.0))
                // Cliente B va segundo con purchaseCount = 0
                .andExpect(jsonPath("$[1].code").value("CUST-002"))
                .andExpect(jsonPath("$[1].name").value("Bernardo López"))
                .andExpect(jsonPath("$[1].purchaseCount").value(0))
                .andExpect(jsonPath("$[1].topProducts.length()").value(0));
    }

    @Test
    void tenantIsolationPreventsCrossTenantDataLeakage() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA, List.of("admin.customers.read"));

        insertCustomer(tenantA.getId(), "CUST-A", "Cliente Tenant A", "a@example.com", "active");
        insertCustomer(tenantB.getId(), "CUST-B", "Cliente Tenant B", "b@example.com", "active");

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("CUST-A"))
                .andExpect(jsonPath("$[0].name").value("Cliente Tenant A"));
    }

    @Test
    void cancelledOrdersAndSalesAreExcludedFromPurchaseCalculations() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));
        Fixture fixture = createFixture(tenant.getId());

        UUID customer = insertCustomer(tenant.getId(), "CUST-CAN", "Carlos Cancelado", "carlos@example.com", "active");

        UUID cancelledOrder = insertOrder(tenant.getId(), fixture.branchId(), customer, "ORD-CAN", "cancelled");
        insertOrderItem(cancelledOrder, fixture.product1Id(), "SKU-1", "Producto Uno", new BigDecimal("10.000"));

        UUID cancelledSale = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customer, null, "SALE-CAN", "cancelled");
        insertSaleItem(cancelledSale, fixture.product1Id(), "SKU-1", "Producto Uno", new BigDecimal("5.000"));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("CUST-CAN"))
                .andExpect(jsonPath("$[0].purchaseCount").value(0))
                .andExpect(jsonPath("$[0].topProducts.length()").value(0));
    }

    @Test
    void linkedSaleFromOrderIsNotDoubleCounted() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));
        Fixture fixture = createFixture(tenant.getId());

        UUID customer = insertCustomer(tenant.getId(), "CUST-LINK", "Lucía Hernández", "lucia@example.com", "active");

        UUID orderId = insertOrder(tenant.getId(), fixture.branchId(), customer, "ORD-LINK", "confirmed");
        insertOrderItem(orderId, fixture.product1Id(), "SKU-1", "Taladro percutor", new BigDecimal("2.000"));

        // Venta generada desde el pedido, del mismo cliente
        UUID saleId = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customer, orderId, "SALE-LINK", "completed");
        insertSaleItem(saleId, fixture.product1Id(), "SKU-1", "Taladro percutor", new BigDecimal("2.000"));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("CUST-LINK"))
                .andExpect(jsonPath("$[0].purchaseCount").value(1)) // 1, no 2
                .andExpect(jsonPath("$[0].topProducts[0].totalQuantity").value(2.0));
    }

    @Test
    void customersWithSamePurchaseCountAreSortedBySpanishAlphabeticalOrder() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));

        insertCustomer(tenant.getId(), "CUST-Z", "Zoila Pérez", "zoila@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-O", "Óscar Ramírez", "oscar@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-NY", "Ñusta Mamani", "nusta@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-N", "Nora Castillo", "nora@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-B", "Bruno Díaz", "bruno@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-A", "Álvaro Gómez", "alvaro@example.com", "active");

        // Á va junto a la A y la Ñ después de la N, no al final como en el orden por código Unicode.
        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].name").value("Álvaro Gómez"))
                .andExpect(jsonPath("$[1].name").value("Bruno Díaz"))
                .andExpect(jsonPath("$[2].name").value("Nora Castillo"))
                .andExpect(jsonPath("$[3].name").value("Ñusta Mamani"))
                .andExpect(jsonPath("$[4].name").value("Óscar Ramírez"))
                .andExpect(jsonPath("$[5].name").value("Zoila Pérez"));
    }

    @Test
    void topProductsWithSameQuantityAreSortedBySpanishAlphabeticalOrder() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));
        Fixture fixture = createFixture(tenant.getId());

        UUID customer = insertCustomer(tenant.getId(), "CUST-PIN", "María Muñoz", "maria@example.com", "active");
        UUID orderId = insertOrder(tenant.getId(), fixture.branchId(), customer, "ORD-PIN", "confirmed");
        insertOrderItem(orderId, fixture.product1Id(), "SKU-PIN", "Pintura látex", new BigDecimal("1.000"));
        insertOrderItem(orderId, fixture.product1Id(), "SKU-OLE", "Óleo azul", new BigDecimal("1.000"));
        insertOrderItem(orderId, fixture.product2Id(), "SKU-BRO", "Brocha 2 pulgadas", new BigDecimal("1.000"));

        mockMvc.perform(get(BASE_URL + "/" + customer).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topProducts.length()").value(3))
                .andExpect(jsonPath("$.topProducts[0].productName").value("Brocha 2 pulgadas"))
                .andExpect(jsonPath("$.topProducts[1].productName").value("Óleo azul"))
                .andExpect(jsonPath("$.topProducts[2].productName").value("Pintura látex"));
    }

    @Test
    void getCustomerByIdCountsOnlyItsOwnPurchasesWithoutDuplicatingLinkedSales() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));
        Fixture fixture = createFixture(tenant.getId());

        UUID customerA = insertCustomer(tenant.getId(), "CUST-A", "Andrés Solís", "andres@example.com", "active");
        UUID customerB = insertCustomer(tenant.getId(), "CUST-B", "Begoña Ruiz", "begona@example.com", "active");

        // Cliente A: 1 pedido, la venta generada desde ese pedido y 1 venta directa -> 2 compras
        UUID orderA = insertOrder(tenant.getId(), fixture.branchId(), customerA, "ORD-A", "confirmed");
        insertOrderItem(orderA, fixture.product1Id(), "SKU-1", "Martillo 16 oz", new BigDecimal("2.000"));
        UUID linkedSale = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customerA, orderA, "V-A-1", "completed");
        insertSaleItem(linkedSale, fixture.product1Id(), "SKU-1", "Martillo 16 oz", new BigDecimal("2.000"));
        UUID directSale = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customerA, null, "V-A-2", "completed");
        insertSaleItem(directSale, fixture.product2Id(), "SKU-2", "Clavos 2 pulgadas", new BigDecimal("1.000"));

        // Cliente B: sus compras no deben aparecer en el detalle de A
        UUID orderB = insertOrder(tenant.getId(), fixture.branchId(), customerB, "ORD-B", "confirmed");
        insertOrderItem(orderB, fixture.product2Id(), "SKU-2", "Clavos 2 pulgadas", new BigDecimal("50.000"));

        mockMvc.perform(get(BASE_URL + "/" + customerA).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Andrés Solís"))
                .andExpect(jsonPath("$.purchaseCount").value(2))
                .andExpect(jsonPath("$.topProducts.length()").value(2))
                .andExpect(jsonPath("$.topProducts[0].productName").value("Martillo 16 oz"))
                .andExpect(jsonPath("$.topProducts[0].totalQuantity").value(2.0))
                .andExpect(jsonPath("$.topProducts[1].productName").value("Clavos 2 pulgadas"))
                .andExpect(jsonPath("$.topProducts[1].totalQuantity").value(1.0));
    }

    @Test
    void listCustomersFilteredByStatus() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));

        insertCustomer(tenant.getId(), "CUST-ACT", "Activo", "act@example.com", "active");
        insertCustomer(tenant.getId(), "CUST-INA", "Inactivo", "ina@example.com", "inactive");

        mockMvc.perform(get(BASE_URL + "?status=active").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("CUST-ACT"));

        mockMvc.perform(get(BASE_URL + "?status=inactive").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("CUST-INA"));
    }

    @Test
    void getCustomerByIdSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));
        UUID customerId = insertCustomer(tenant.getId(), "CUST-SINGLE", "José Pérez", "solo@example.com", "active");

        mockMvc.perform(get(BASE_URL + "/" + customerId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId.toString()))
                .andExpect(jsonPath("$.code").value("CUST-SINGLE"))
                .andExpect(jsonPath("$.name").value("José Pérez"))
                .andExpect(jsonPath("$.purchaseCount").value(0))
                .andExpect(jsonPath("$.topProducts").isArray());
    }

    @Test
    void getCustomerByIdNotFoundReturns404() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.customers.read"));

        mockMvc.perform(get(BASE_URL + "/" + UUID.randomUUID()).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void getCustomerByIdFromOtherTenantReturns404() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA, List.of("admin.customers.read"));
        UUID customerB = insertCustomer(tenantB.getId(), "CUST-B", "Cliente de otra tienda", "otro@example.com", "active");

        mockMvc.perform(get(BASE_URL + "/" + customerB).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    // --- Utilidades ---

    private UUID insertCustomer(UUID tenantId, String code, String name, String email, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO customers (id, tenant_id, code, name, email, phone, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '12345678', ?, now(), now())
                """,
                id, tenantId, code, name, email, status);
        return id;
    }

    private UUID insertOrder(UUID tenantId, UUID branchId, UUID customerId, String orderNumber, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, customer_id, status,
                                    delivery_method, transport_mode, subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', ?, ?, 'store_pickup', 'none', 100.00, 0.00, 0.00, 100.00, ?)
                """,
                id, tenantId, branchId, orderNumber, customerId, status, UUID.randomUUID().toString());
        return id;
    }

    private void insertOrderItem(UUID orderId, UUID productId, String sku, String name, BigDecimal quantity) {
        jdbcTemplate.update(
                """
                INSERT INTO order_items (id, order_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, ?, ?, ?, 50.00, 0.00, 50.00)
                """,
                UUID.randomUUID(), orderId, productId, sku, name, quantity);
    }

    private UUID insertSale(UUID tenantId, UUID branchId, UUID cashShiftId, UUID userId,
                            UUID customerId, UUID sourceOrderId, String number, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sales (id, tenant_id, branch_id, cash_shift_id, created_by_user_id, customer_id,
                                   source_order_id, number, status, subtotal, discount_total, tax_total, total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 100.00, 0.00, 0.00, 100.00)
                """,
                id, tenantId, branchId, cashShiftId, userId, customerId, sourceOrderId, number, status);
        return id;
    }

    private void insertSaleItem(UUID saleId, UUID productId, String sku, String name, BigDecimal quantity) {
        jdbcTemplate.update(
                """
                INSERT INTO sale_items (id, sale_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, ?, ?, ?, 50.00, 0.00, 50.00)
                """,
                UUID.randomUUID(), saleId, productId, sku, name, quantity);
    }

    private Fixture createFixture(UUID tenantId) {
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID product1Id = UUID.randomUUID();
        UUID product2Id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID cashShiftId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """,
                branchId, tenantId, "BR-" + suffix.substring(0, 8), "Sucursal " + suffix);

        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, ?, ?, 'active')
                """,
                categoryId, tenantId, "Categoría " + suffix, "cat-" + suffix);

        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
                """,
                unitId, tenantId, "U-" + suffix.substring(0, 8));

        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Martillo 16 oz', ?, ?)
                """,
                product1Id, tenantId, "SKU-1-" + suffix, categoryId, unitId);

        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Clavos 2 pulgadas', ?, ?)
                """,
                product2Id, tenantId, "SKU-2-" + suffix, categoryId, unitId);

        jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Cajero Prueba', ?, 'employee', 'active', ?)
                """,
                userId, tenantId, "cashier-" + suffix + "@omniretail.local", branchId);

        jdbcTemplate.update(
                """
                INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at, opening_amount)
                VALUES (?, ?, ?, ?, 'REG-01', 'open', now(), 100.00)
                """,
                cashShiftId, tenantId, branchId, userId);

        return new Fixture(branchId, product1Id, product2Id, userId, cashShiftId);
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build();
        return tenantRepository.save(tenant);
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role actorRole = Role.builder()
                .name("Rol Actor " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        actorRole.setTenantId(tenant.getId());
        actorRole = roleRepository.save(actorRole);

        User user = User.builder()
                .name("Empleado Prueba")
                .email("test-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        Session session = Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        session = sessionRepository.save(session);

        return jwtService.generateToken(user, session);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Fixture(UUID branchId, UUID product1Id, UUID product2Id, UUID userId, UUID cashShiftId) {}
}
