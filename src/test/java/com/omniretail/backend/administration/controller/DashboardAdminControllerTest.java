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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
class DashboardAdminControllerTest {

    private static final String BASE_URL = "/api/v1/administration/dashboard";
    private static final List<String> DASHBOARD_READ = List.of("admin.dashboard.read");
    private static final ZoneId TENANT_ZONE = ZoneId.of("America/Guatemala");

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
    void getDashboardSummaryEmptyTenantReturnsZeroedKpis() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        int daysSoFar = today().getDayOfMonth();

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesToday.amount").value(0))
                .andExpect(jsonPath("$.salesToday.count").value(0))
                .andExpect(jsonPath("$.salesMonth.amount").value(0))
                .andExpect(jsonPath("$.salesMonth.count").value(0))
                .andExpect(jsonPath("$.salesByBranch.length()").value(0))
                // La serie diaria siempre trae un punto por día del mes hasta hoy, aunque sea en cero.
                .andExpect(jsonPath("$.dailySalesMonth.length()").value(daysSoFar))
                .andExpect(jsonPath("$.dailySalesMonth[0].amount").value(0))
                .andExpect(jsonPath("$.stockAlerts.outOfStock").value(0))
                .andExpect(jsonPath("$.stockAlerts.lowStock").value(0))
                .andExpect(jsonPath("$.stockAlertsByBranch.length()").value(0))
                .andExpect(jsonPath("$.pendingOrders").value(0))
                .andExpect(jsonPath("$.pendingOrdersByStatus.confirmed").value(0))
                .andExpect(jsonPath("$.pendingOrdersByStatus.ready_for_dispatch").value(0))
                .andExpect(jsonPath("$.pendingOrdersByBranch.length()").value(0))
                .andExpect(jsonPath("$.incidentAnalytics.totalCurrentMonth").value(0))
                .andExpect(jsonPath("$.incidentAnalytics.byType.length()").value(0))
                .andExpect(jsonPath("$.incidentAnalytics.bySupplier.length()").value(0))
                .andExpect(jsonPath("$.latestIncidents.length()").value(0))
                .andExpect(jsonPath("$.topProducts.length()").value(0));
    }

    @Test
    void getDashboardSummaryAggregatesSalesTodayAndMonth() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        Fixture fixture = createFixture(tenant.getId());
        LocalDate today = today();

        insertSale(tenant.getId(), fixture.branchA(), fixture, "V-HOY-1", "completed", "100.00", Instant.now());
        insertSale(tenant.getId(), fixture.branchA(), fixture, "V-HOY-2", "partially_returned", "50.50",
                Instant.now());
        insertSale(tenant.getId(), fixture.branchA(), fixture, "V-HOY-CANCELADA", "cancelled", "999.00",
                Instant.now());
        insertSale(tenant.getId(), fixture.branchA(), fixture, "V-MES-ANTERIOR", "completed", "777.00",
                noon(today.withDayOfMonth(1).minusDays(1)));

        // Una venta de otro día del mismo mes solo es posible después del día 1.
        boolean hasEarlierDay = today.getDayOfMonth() > 1;
        if (hasEarlierDay) {
            insertSale(tenant.getId(), fixture.branchB(), fixture, "V-MES", "completed", "200.00",
                    noon(today.minusDays(1)));
        }

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesToday.amount").value(150.5))
                .andExpect(jsonPath("$.salesToday.count").value(2))
                .andExpect(jsonPath("$.salesMonth.amount").value(hasEarlierDay ? 350.5 : 150.5))
                .andExpect(jsonPath("$.salesMonth.count").value(hasEarlierDay ? 3 : 2));
    }

    @Test
    void getDashboardSummaryAggregatesSalesByBranchAndDailySales() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        Fixture fixture = createFixture(tenant.getId());
        LocalDate today = today();

        insertSale(tenant.getId(), fixture.branchA(), fixture, "V-CENTRO-1", "completed", "80.00", Instant.now());
        insertSale(tenant.getId(), fixture.branchB(), fixture, "V-NORTE-1", "completed", "120.00", Instant.now());
        insertSale(tenant.getId(), fixture.branchB(), fixture, "V-NORTE-2", "completed", "30.00", Instant.now());
        int lastDay = today.getDayOfMonth() - 1;

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                // Mayor monto primero.
                .andExpect(jsonPath("$.salesByBranch.length()").value(2))
                .andExpect(jsonPath("$.salesByBranch[0].branchName").value(fixture.branchBName()))
                .andExpect(jsonPath("$.salesByBranch[0].amount").value(150.0))
                .andExpect(jsonPath("$.salesByBranch[0].count").value(2))
                .andExpect(jsonPath("$.salesByBranch[1].branchName").value(fixture.branchAName()))
                .andExpect(jsonPath("$.salesByBranch[1].amount").value(80.0))
                .andExpect(jsonPath("$.salesByBranch[1].count").value(1))
                .andExpect(jsonPath("$.dailySalesMonth.length()").value(today.getDayOfMonth()))
                .andExpect(jsonPath("$.dailySalesMonth[0].date").value(today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.dailySalesMonth[" + lastDay + "].date").value(today.toString()))
                .andExpect(jsonPath("$.dailySalesMonth[" + lastDay + "].amount").value(230.0))
                .andExpect(jsonPath("$.dailySalesMonth[" + lastDay + "].count").value(3));
    }

    @Test
    void getDashboardSummaryAggregatesTopProductsSortedByQuantityDesc() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        Fixture fixture = createFixture(tenant.getId());
        UUID product = insertProduct(tenant.getId(), fixture, "Producto base", "published", true);

        UUID saleOne = insertSale(tenant.getId(), fixture.branchA(), fixture, "V-1", "completed", "300.00",
                Instant.now());
        insertSaleItem(saleOne, product, "Clavos 2 pulgadas", "10.000", "50.00");
        insertSaleItem(saleOne, product, "Martillo 16 oz", "2.000", "150.00");
        UUID saleTwo = insertSale(tenant.getId(), fixture.branchB(), fixture, "V-2", "completed", "100.00",
                Instant.now());
        insertSaleItem(saleTwo, product, "Martillo 16 oz", "1.000", "75.00");
        insertSaleItem(saleTwo, product, "Pintura látex", "4.000", "25.00");
        UUID cancelled = insertSale(tenant.getId(), fixture.branchA(), fixture, "V-CANCELADA", "cancelled",
                "900.00", Instant.now());
        insertSaleItem(cancelled, product, "Martillo 16 oz", "90.000", "900.00");

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topProducts.length()").value(3))
                .andExpect(jsonPath("$.topProducts[0].productName").value("Clavos 2 pulgadas"))
                .andExpect(jsonPath("$.topProducts[0].totalQuantity").value(10.0))
                .andExpect(jsonPath("$.topProducts[0].totalRevenue").value(50.0))
                .andExpect(jsonPath("$.topProducts[1].productName").value("Pintura látex"))
                .andExpect(jsonPath("$.topProducts[1].totalQuantity").value(4.0))
                .andExpect(jsonPath("$.topProducts[2].productName").value("Martillo 16 oz"))
                .andExpect(jsonPath("$.topProducts[2].totalQuantity").value(3.0))
                .andExpect(jsonPath("$.topProducts[2].totalRevenue").value(225.0));
    }

    @Test
    void getDashboardSummaryAggregatesStockAlerts() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        Fixture fixture = createFixture(tenant.getId());

        UUID withStock = insertProduct(tenant.getId(), fixture, "Con existencia", "published", true);
        UUID fullyReserved = insertProduct(tenant.getId(), fixture, "Todo reservado", "published", true);
        UUID emptyBalance = insertProduct(tenant.getId(), fixture, "Balance en cero", "published", true);
        insertProduct(tenant.getId(), fixture, "Sin balance", "published", true);
        // No cuentan: producto archivado y producto que no controla stock.
        insertProduct(tenant.getId(), fixture, "Archivado", "archived", true);
        insertProduct(tenant.getId(), fixture, "Servicio sin stock", "published", false);

        insertBalance(tenant.getId(), fixture.branchA(), withStock, "10.000", "2.000");
        insertBalance(tenant.getId(), fixture.branchA(), fullyReserved, "5.000", "5.000");
        insertBalance(tenant.getId(), fixture.branchA(), emptyBalance, "0.000", "0.000");
        insertBalance(tenant.getId(), fixture.branchB(), withStock, "3.000", "0.000");

        // Centro: sin stock = todo reservado, balance en cero y sin balance (3).
        // Norte: solo "Con existencia" tiene disponible, los otros 3 no (3).
        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockAlerts.outOfStock").value(6))
                .andExpect(jsonPath("$.stockAlerts.lowStock").value(0))
                .andExpect(jsonPath("$.stockAlertsByBranch.length()").value(2))
                .andExpect(jsonPath("$.stockAlertsByBranch[0].branchId").value(fixture.branchA().toString()))
                .andExpect(jsonPath("$.stockAlertsByBranch[0].branchName").value(fixture.branchAName()))
                .andExpect(jsonPath("$.stockAlertsByBranch[0].outOfStock").value(3))
                .andExpect(jsonPath("$.stockAlertsByBranch[0].lowStock").value(0))
                .andExpect(jsonPath("$.stockAlertsByBranch[0].total").value(3))
                .andExpect(jsonPath("$.stockAlertsByBranch[1].branchId").value(fixture.branchB().toString()))
                .andExpect(jsonPath("$.stockAlertsByBranch[1].outOfStock").value(3));
    }

    @Test
    void getDashboardSummaryAggregatesPendingOrdersByStatusAndBranch() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        Fixture fixture = createFixture(tenant.getId());

        insertOrder(tenant.getId(), fixture.branchA(), "P-1", "confirmed");
        insertOrder(tenant.getId(), fixture.branchB(), "P-2", "confirmed");
        insertOrder(tenant.getId(), fixture.branchB(), "P-3", "preparing");
        insertOrder(tenant.getId(), fixture.branchB(), "P-4", "ready_for_dispatch");
        // No son pendientes de logística.
        insertOrder(tenant.getId(), fixture.branchA(), "P-5", "delivered");
        insertOrder(tenant.getId(), fixture.branchA(), "P-6", "pending");
        insertOrder(tenant.getId(), fixture.branchA(), "P-7", "cancelled");

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrders").value(4))
                .andExpect(jsonPath("$.pendingOrdersByStatus.confirmed").value(2))
                .andExpect(jsonPath("$.pendingOrdersByStatus.preparing").value(1))
                .andExpect(jsonPath("$.pendingOrdersByStatus.picking").value(0))
                .andExpect(jsonPath("$.pendingOrdersByStatus.packing").value(0))
                .andExpect(jsonPath("$.pendingOrdersByStatus.ready_for_dispatch").value(1))
                .andExpect(jsonPath("$.pendingOrdersByBranch.length()").value(2))
                .andExpect(jsonPath("$.pendingOrdersByBranch[0].branchName").value(fixture.branchBName()))
                .andExpect(jsonPath("$.pendingOrdersByBranch[0].count").value(3))
                .andExpect(jsonPath("$.pendingOrdersByBranch[1].branchName").value(fixture.branchAName()))
                .andExpect(jsonPath("$.pendingOrdersByBranch[1].count").value(1));
    }

    @Test
    void branchesWithSameTotalsAreSortedBySpanishAlphabeticalOrder() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, DASHBOARD_READ);
        insertBranch(tenant.getId(), "Zacapa", "store", "active");
        insertBranch(tenant.getId(), "Ñuñoa", "store", "active");
        insertBranch(tenant.getId(), "Antigua", "main", "active");
        insertBranch(tenant.getId(), "Nebaj", "store", "active");
        // Una sucursal inactiva no aparece en los desgloses.
        insertBranch(tenant.getId(), "Cerrada", "store", "inactive");

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesByBranch.length()").value(4))
                .andExpect(jsonPath("$.salesByBranch[0].branchName").value("Antigua"))
                .andExpect(jsonPath("$.salesByBranch[1].branchName").value("Nebaj"))
                .andExpect(jsonPath("$.salesByBranch[2].branchName").value("Ñuñoa"))
                .andExpect(jsonPath("$.salesByBranch[3].branchName").value("Zacapa"))
                .andExpect(jsonPath("$.pendingOrdersByBranch[2].branchName").value("Ñuñoa"))
                .andExpect(jsonPath("$.stockAlertsByBranch[2].branchName").value("Ñuñoa"));
    }

    @Test
    void tenantIsolationPreventsCrossTenantDataLeak() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA, DASHBOARD_READ);
        Fixture fixtureB = createFixture(tenantB.getId());

        UUID productB = insertProduct(tenantB.getId(), fixtureB, "Producto de B", "published", true);
        UUID saleB = insertSale(tenantB.getId(), fixtureB.branchA(), fixtureB, "V-B", "completed", "500.00",
                Instant.now());
        insertSaleItem(saleB, productB, "Producto de B", "5.000", "500.00");
        insertOrder(tenantB.getId(), fixtureB.branchA(), "P-B", "confirmed");
        insertBalance(tenantB.getId(), fixtureB.branchA(), productB, "0.000", "0.000");

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesToday.count").value(0))
                .andExpect(jsonPath("$.salesMonth.amount").value(0))
                .andExpect(jsonPath("$.salesByBranch.length()").value(0))
                .andExpect(jsonPath("$.topProducts.length()").value(0))
                .andExpect(jsonPath("$.pendingOrders").value(0))
                .andExpect(jsonPath("$.pendingOrdersByBranch.length()").value(0))
                .andExpect(jsonPath("$.stockAlerts.outOfStock").value(0))
                .andExpect(jsonPath("$.stockAlertsByBranch.length()").value(0));
    }

    // --- Utilidades ---

    private static LocalDate today() {
        return LocalDate.now(TENANT_ZONE);
    }

    private static Instant noon(LocalDate date) {
        return date.atTime(LocalTime.NOON).atZone(TENANT_ZONE).toInstant();
    }

    private UUID insertSale(
            UUID tenantId, UUID branchId, Fixture fixture, String number, String status, String total,
            Instant createdAt) {
        UUID id = UUID.randomUUID();
        UUID cashShiftId = branchId.equals(fixture.branchA()) ? fixture.cashShiftA() : fixture.cashShiftB();
        jdbcTemplate.update(
                """
                INSERT INTO sales (id, tenant_id, branch_id, cash_shift_id, created_by_user_id, number, status,
                                   subtotal, discount_total, tax_total, total, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.00, 0.00, ?, ?, ?)
                """,
                id, tenantId, branchId, cashShiftId, fixture.userId(), number, status,
                new BigDecimal(total), new BigDecimal(total), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private void insertSaleItem(UUID saleId, UUID productId, String name, String quantity, String subtotal) {
        jdbcTemplate.update(
                """
                INSERT INTO sale_items (id, sale_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price,
                                        discount, subtotal)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0.00, ?)
                """,
                UUID.randomUUID(), saleId, productId, "SKU-" + UUID.randomUUID().toString().substring(0, 8), name,
                new BigDecimal(quantity), new BigDecimal(subtotal), new BigDecimal(subtotal));
    }

    private void insertOrder(UUID tenantId, UUID branchId, String orderNumber, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, status, delivery_method,
                                    transport_mode, subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'pos', ?, 'store_pickup', 'none', 100.00, 0.00, 0.00, 100.00, ?)
                """,
                UUID.randomUUID(), tenantId, branchId, orderNumber, status, UUID.randomUUID().toString());
    }

    private void insertBalance(UUID tenantId, UUID branchId, UUID productId, String quantity, String reserved) {
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, branchId, productId, new BigDecimal(quantity), new BigDecimal(reserved));
    }

    private UUID insertProduct(UUID tenantId, Fixture fixture, String name, String status, boolean trackingStock) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, status, tracking_stock)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, "SKU-" + UUID.randomUUID(), name, fixture.categoryId(), fixture.unitId(), status,
                trackingStock);
        return id;
    }

    private UUID insertBranch(UUID tenantId, String name, String type, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, "BR-" + UUID.randomUUID().toString().substring(0, 8), name, type, status);
        return id;
    }

    private Fixture createFixture(UUID tenantId) {
        String branchAName = "Centro";
        String branchBName = "Norte";
        UUID branchA = insertBranch(tenantId, branchAName, "main", "active");
        UUID branchB = insertBranch(tenantId, branchBName, "store", "active");
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID cashShiftA = UUID.randomUUID();
        UUID cashShiftB = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

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
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Cajera Prueba', ?, 'employee', 'active', ?)
                """,
                userId, tenantId, "cajera-" + suffix + "@omniretail.local", branchA);
        for (UUID[] shift : new UUID[][] {{cashShiftA, branchA}, {cashShiftB, branchB}}) {
            jdbcTemplate.update(
                    """
                    INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at,
                                             opening_amount)
                    VALUES (?, ?, ?, ?, 'CAJA-01', 'open', now(), 100.00)
                    """,
                    shift[0], tenantId, shift[1], userId);
        }
        return new Fixture(branchA, branchAName, branchB, branchBName, categoryId, unitId, userId, cashShiftA,
                cashShiftB);
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone(TENANT_ZONE.getId())
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
            UUID branchA,
            String branchAName,
            UUID branchB,
            String branchBName,
            UUID categoryId,
            UUID unitId,
            UUID userId,
            UUID cashShiftA,
            UUID cashShiftB) {}
}
