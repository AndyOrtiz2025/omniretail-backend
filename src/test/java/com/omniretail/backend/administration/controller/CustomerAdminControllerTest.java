package com.omniretail.backend.administration.controller;

import static org.assertj.core.api.Assertions.assertThat;
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

        UUID customerA = insertCustomer(tenant.getId(), "CUST-001", "Ana Martinez", "ana@example.com", "active");
        UUID customerB = insertCustomer(tenant.getId(), "CUST-002", "Bernardo Lopez", "bernardo@example.com", "active");

        // Customer A: 1 Order with 2 products ("Martillo" x 2.0, "Clavos" x 5.0)
        UUID orderId = insertOrder(tenant.getId(), fixture.branchId(), customerA, "ORD-1001", "confirmed");
        insertOrderItem(orderId, fixture.product1Id(), "SKU-MARTILLO", "Martillo 16oz", new BigDecimal("2.000"));
        insertOrderItem(orderId, fixture.product2Id(), "SKU-CLAVOS", "Clavos 2in", new BigDecimal("5.000"));

        // Customer A: 1 Direct Sale with 1 product ("Martillo" x 1.0)
        UUID saleId = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customerA, null, "V-1001", "completed");
        insertSaleItem(saleId, fixture.product1Id(), "SKU-MARTILLO", "Martillo 16oz", new BigDecimal("1.000"));

        // Customer B has 0 purchases

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Customer A is first because purchaseCount = 2
                .andExpect(jsonPath("$[0].code").value("CUST-001"))
                .andExpect(jsonPath("$[0].name").value("Ana Martinez"))
                .andExpect(jsonPath("$[0].purchaseCount").value(2))
                .andExpect(jsonPath("$[0].topProducts.length()").value(2))
                .andExpect(jsonPath("$[0].topProducts[0].productName").value("Clavos 2in"))
                .andExpect(jsonPath("$[0].topProducts[0].totalQuantity").value(5.0))
                .andExpect(jsonPath("$[0].topProducts[1].productName").value("Martillo 16oz"))
                .andExpect(jsonPath("$[0].topProducts[1].totalQuantity").value(3.0))
                // Customer B is second with purchaseCount = 0
                .andExpect(jsonPath("$[1].code").value("CUST-002"))
                .andExpect(jsonPath("$[1].name").value("Bernardo Lopez"))
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

        UUID customer = insertCustomer(tenant.getId(), "CUST-LINK", "Lucia Link", "lucia@example.com", "active");

        UUID orderId = insertOrder(tenant.getId(), fixture.branchId(), customer, "ORD-LINK", "confirmed");
        insertOrderItem(orderId, fixture.product1Id(), "SKU-1", "Producto Link", new BigDecimal("2.000"));

        // Sale linked to the order for the same customer
        UUID saleId = insertSale(tenant.getId(), fixture.branchId(), fixture.cashShiftId(), fixture.userId(),
                customer, orderId, "SALE-LINK", "completed");
        insertSaleItem(saleId, fixture.product1Id(), "SKU-1", "Producto Link", new BigDecimal("2.000"));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("CUST-LINK"))
                .andExpect(jsonPath("$[0].purchaseCount").value(1)) // 1, not 2
                .andExpect(jsonPath("$[0].topProducts[0].totalQuantity").value(2.0));
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
        UUID customerId = insertCustomer(tenant.getId(), "CUST-SINGLE", "Solo Uno", "solo@example.com", "active");

        mockMvc.perform(get(BASE_URL + "/" + customerId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId.toString()))
                .andExpect(jsonPath("$.code").value("CUST-SINGLE"))
                .andExpect(jsonPath("$.name").value("Solo Uno"))
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
        UUID customerB = insertCustomer(tenantB.getId(), "CUST-B", "De Otro", "otro@example.com", "active");

        mockMvc.perform(get(BASE_URL + "/" + customerB).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    // --- Helpers ---

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
                INSERT INTO sale_items (id, sale_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price, discount, tax, total)
                VALUES (?, ?, ?, ?, ?, ?, 50.00, 0.00, 0.00, 50.00)
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
                categoryId, tenantId, "Cat " + suffix, "cat-" + suffix);

        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
                """,
                unitId, tenantId, "U-" + suffix.substring(0, 8));

        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Martillo 16oz', ?, ?)
                """,
                product1Id, tenantId, "SKU-1-" + suffix, categoryId, unitId);

        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Clavos 2in', ?, ?)
                """,
                product2Id, tenantId, "SKU-2-" + suffix, categoryId, unitId);

        jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'User Cashier', ?, 'employee', 'active', ?)
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
                .name("Empleado Test")
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
