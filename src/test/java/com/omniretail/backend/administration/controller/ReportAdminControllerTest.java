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
import java.math.RoundingMode;
import java.sql.Timestamp;
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
class ReportAdminControllerTest {

    private static final String BASE_URL = "/api/v1/administration/reports";
    private static final List<String> REPORTS_READ = List.of("admin.reports.read");

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
    void getReportsEmptyTenantReturnsEmptyLists() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, REPORTS_READ);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.sales.length()").value(0))
                .andExpect(jsonPath("$.purchases.length()").value(0))
                .andExpect(jsonPath("$.movements.length()").value(0))
                .andExpect(jsonPath("$.payments.length()").value(0));
    }

    @Test
    void getReportsCombinesPosSalesAndEcommerceOrdersSortedDesc() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, REPORTS_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        insertOrder(tenant.getId(), fixture, "APP-1", "mobileApp", "pending", "40.00", now.minus(3, ChronoUnit.HOURS));
        insertSale(tenant.getId(), fixture, "V-0001", "completed", "112.00", now.minus(2, ChronoUnit.HOURS));
        insertOrder(tenant.getId(), fixture, "WEB-1", "ecommerce", "confirmed", "250.00",
                now.minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sales.length()").value(3))
                // Más reciente primero: pedido web, venta física y pedido de la app.
                .andExpect(jsonPath("$.sales[0].number").value("WEB-1"))
                .andExpect(jsonPath("$.sales[0].channel").value("En línea"))
                .andExpect(jsonPath("$.sales[0].origin").value("Tienda en línea"))
                .andExpect(jsonPath("$.sales[0].status").value("completed"))
                .andExpect(jsonPath("$.sales[0].taxTotal").value(0))
                .andExpect(jsonPath("$.sales[0].total").value(250.0))
                .andExpect(jsonPath("$.sales[0].branchName").value(fixture.branchName()))
                .andExpect(jsonPath("$.sales[1].number").value("V-0001"))
                .andExpect(jsonPath("$.sales[1].channel").value("POS"))
                .andExpect(jsonPath("$.sales[1].origin").value("Venta física"))
                .andExpect(jsonPath("$.sales[1].status").value("completed"))
                .andExpect(jsonPath("$.sales[1].subtotal").value(100.0))
                .andExpect(jsonPath("$.sales[1].taxTotal").value(12.0))
                .andExpect(jsonPath("$.sales[1].total").value(112.0))
                .andExpect(jsonPath("$.sales[1].branchId").value(fixture.branchId().toString()))
                // Un pedido pendiente se reporta como venta cancelada.
                .andExpect(jsonPath("$.sales[2].number").value("APP-1"))
                .andExpect(jsonPath("$.sales[2].origin").value("App Móvil"))
                .andExpect(jsonPath("$.sales[2].status").value("cancelled"));
    }

    @Test
    void getReportsExcludesPosOrdersFromEcommerceStream() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, REPORTS_READ);
        Fixture fixture = createFixture(tenant.getId());

        insertOrder(tenant.getId(), fixture, "POS-ORD-1", "pos", "confirmed", "80.00", Instant.now());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sales.length()").value(0));
    }

    @Test
    void getReportsMapsInventoryMovementsWithNames() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, REPORTS_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        insertMovement(tenant.getId(), fixture, "in", "25.000", "Recepción de proveedor",
                now.minus(2, ChronoUnit.HOURS));
        insertMovement(tenant.getId(), fixture, "out", "3.000", "Ajuste por daño", now.minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movements.length()").value(2))
                .andExpect(jsonPath("$.movements[0].type").value("out"))
                .andExpect(jsonPath("$.movements[0].reason").value("Ajuste por daño"))
                .andExpect(jsonPath("$.movements[0].quantity").value(3.0))
                .andExpect(jsonPath("$.movements[0].branchId").value(fixture.branchId().toString()))
                .andExpect(jsonPath("$.movements[0].branchName").value(fixture.branchName()))
                .andExpect(jsonPath("$.movements[0].productId").value(fixture.productId().toString()))
                .andExpect(jsonPath("$.movements[0].productName").value("Martillo de uña 16 oz"))
                .andExpect(jsonPath("$.movements[1].type").value("in"))
                .andExpect(jsonPath("$.movements[1].reason").value("Recepción de proveedor"));
    }

    @Test
    void getReportsMapsPaymentsWithOriginAndFallbackReference() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, REPORTS_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        UUID orderId = insertOrder(tenant.getId(), fixture, "WEB-9", "ecommerce", "confirmed", "250.00",
                now.minus(5, ChronoUnit.HOURS));
        UUID saleId = insertSale(tenant.getId(), fixture, "V-0009", "completed", "112.00",
                now.minus(5, ChronoUnit.HOURS));

        insertPayment(tenant.getId(), orderId, null, "card", "approved", "250.00", "AUT-5521",
                now.minus(3, ChronoUnit.HOURS));
        insertPayment(tenant.getId(), null, saleId, "cash", "approved", "112.00", "   ",
                now.minus(2, ChronoUnit.HOURS));
        insertPayment(tenant.getId(), null, null, "transfer", "pending", "60.00", null,
                now.minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()").value(3))
                // Sin orden ni venta: origen y referencia "—".
                .andExpect(jsonPath("$.payments[0].method").value("transfer"))
                .andExpect(jsonPath("$.payments[0].status").value("pending"))
                .andExpect(jsonPath("$.payments[0].origin").value("—"))
                .andExpect(jsonPath("$.payments[0].reference").value("—"))
                // Referencia en blanco también se muestra como "—".
                .andExpect(jsonPath("$.payments[1].origin").value("Venta"))
                .andExpect(jsonPath("$.payments[1].reference").value("—"))
                .andExpect(jsonPath("$.payments[1].amount").value(112.0))
                .andExpect(jsonPath("$.payments[2].origin").value("Orden"))
                .andExpect(jsonPath("$.payments[2].reference").value("AUT-5521"))
                .andExpect(jsonPath("$.payments[2].method").value("card"));
    }

    @Test
    void tenantIsolationPreventsCrossTenantDataLeak() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB, REPORTS_READ);
        Fixture fixtureA = createFixture(tenantA.getId());
        Instant now = Instant.now();

        UUID saleA = insertSale(tenantA.getId(), fixtureA, "V-A", "completed", "112.00", now);
        insertOrder(tenantA.getId(), fixtureA, "WEB-A", "ecommerce", "confirmed", "90.00", now);
        insertMovement(tenantA.getId(), fixtureA, "in", "5.000", "Ingreso inicial", now);
        insertPayment(tenantA.getId(), null, saleA, "cash", "approved", "112.00", null, now);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantB.getId().toString()))
                .andExpect(jsonPath("$.sales.length()").value(0))
                .andExpect(jsonPath("$.purchases.length()").value(0))
                .andExpect(jsonPath("$.movements.length()").value(0))
                .andExpect(jsonPath("$.payments.length()").value(0));
    }

    // --- Utilidades ---

    private UUID insertSale(UUID tenantId, Fixture fixture, String number, String status, String total, Instant at) {
        UUID id = UUID.randomUUID();
        BigDecimal totalAmount = new BigDecimal(total);
        // Total con IVA incluido (12 %): 112.00 -> subtotal 100.00 + IVA 12.00.
        BigDecimal tax = totalAmount.multiply(new BigDecimal("0.12"))
                .divide(new BigDecimal("1.12"), 2, RoundingMode.HALF_UP);
        jdbcTemplate.update(
                """
                INSERT INTO sales (id, tenant_id, branch_id, cash_shift_id, created_by_user_id, number, status,
                                   subtotal, discount_total, tax_total, total, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.00, ?, ?, ?, ?)
                """,
                id, tenantId, fixture.branchId(), fixture.cashShiftId(), fixture.userId(), number, status,
                totalAmount.subtract(tax), tax, totalAmount, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    private UUID insertOrder(
            UUID tenantId, Fixture fixture, String number, String source, String status, String total, Instant at) {
        UUID id = UUID.randomUUID();
        // Un pedido de la tienda en línea exige cliente (ck_orders_ecommerce_customer).
        UUID customerId = "ecommerce".equals(source) ? fixture.customerId() : null;
        jdbcTemplate.update(
                """
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, customer_id, status,
                                    delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                                    total, tracking_token, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'store_pickup', 'none', ?, 0.00, 0.00, ?, ?, ?, ?)
                """,
                id, tenantId, fixture.branchId(), number, source, customerId, status, new BigDecimal(total),
                new BigDecimal(total), UUID.randomUUID().toString(), Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    private void insertMovement(
            UUID tenantId, Fixture fixture, String type, String quantity, String reason, Instant at) {
        jdbcTemplate.update(
                """
                INSERT INTO inventory_movements (id, tenant_id, branch_id, product_id, type, reason, quantity,
                                                 created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, fixture.branchId(), fixture.productId(), type, reason,
                new BigDecimal(quantity), Timestamp.from(at));
    }

    private void insertPayment(
            UUID tenantId,
            UUID orderId,
            UUID saleId,
            String method,
            String status,
            String amount,
            String reference,
            Instant at) {
        jdbcTemplate.update(
                """
                INSERT INTO payments (id, tenant_id, order_id, sale_id, method, status, amount, currency, reference,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'GTQ', ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, orderId, saleId, method, status, new BigDecimal(amount), reference,
                Timestamp.from(at), Timestamp.from(at));
    }

    private Fixture createFixture(UUID tenantId) {
        String suffix = UUID.randomUUID().toString();
        String branchName = "Sucursal Centro " + suffix.substring(0, 8);
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID cashShiftId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "BR-" + suffix.substring(0, 8), branchName);
        jdbcTemplate.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoría " + suffix, "categoria-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """,
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Martillo de uña 16 oz', ?, ?)
                """,
                productId, tenantId, "SKU-" + suffix, categoryId, unitId);
        jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Cajera Prueba', ?, 'employee', 'active', ?)
                """,
                userId, tenantId, "cajera-" + suffix + "@omniretail.local", branchId);
        jdbcTemplate.update(
                """
                INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at,
                                         opening_amount)
                VALUES (?, ?, ?, ?, 'CAJA-01', 'open', now(), 100.00)
                """,
                cashShiftId, tenantId, branchId, userId);
        jdbcTemplate.update(
                """
                INSERT INTO customers (id, tenant_id, code, name, email, phone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'María Muñoz', ?, '22223333', 'active', now(), now())
                """,
                customerId, tenantId, "CLI-" + suffix.substring(0, 8), "maria-" + suffix + "@example.com");
        return new Fixture(branchId, branchName, productId, userId, cashShiftId, customerId);
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
                .name("Gerente Prueba")
                .email("gerente-" + UUID.randomUUID() + "@omniretail.local")
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

    private record Fixture(
            UUID branchId, String branchName, UUID productId, UUID userId, UUID cashShiftId, UUID customerId) {}
}
