package com.omniretail.backend.shared.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.SubscriptionTestFixtures;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Prueba de Oro de Concurrencia: dos operaciones simultaneas sobre la ULTIMA unidad de un producto.
 * PostgreSQL (FOR UPDATE sobre inventory_balances) debe aprobar exactamente una y rechazar la otra
 * con 409 INSUFFICIENT_STOCK, sin stock negativo ni datos huerfanos del perdedor.
 *
 * <p>Sin mocks: rol real con permisos, sesion real, JWT real y turno de caja abierto por HTTP, para
 * recorrer la cadena completa (seguridad, permisos, capacidades, alcance de sucursal, servicios).
 * Cada escenario simultaneo se repite {@link #ROUNDS} veces con una tienda nueva (UUIDs aleatorios);
 * dos casos de orden fijo cubren de forma determinista las dos ramas del escenario POS vs web.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GoldenConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(GoldenConcurrencyTest.class);
    private static final int ROUNDS = 5;
    private static final String PRICE = "10.00";

    @Autowired private SaasPlanRepository saasPlans;
    @Autowired private TenantSubscriptionRepository tenantSubscriptions;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionRepository sessionRepository;

    @Test
    void twoSimultaneousPosSalesOfTheLastUnitApproveExactlyOne() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            Store store = store();
            Cashier first = cashier(store, "CAJA-1");
            Cashier second = cashier(store, "CAJA-2");

            List<Outcome> outcomes = concurrently(() -> sell(store, first), () -> sell(store, second));

            String label = "escenario 1, ronda " + round + ": " + outcomes;
            assertThat(outcomes).as(label).extracting(Outcome::status).containsExactlyInAnyOrder(201, 409);
            assertThat(outcomes).as(label).filteredOn(o -> o.status() == 409).singleElement()
                    .extracting(Outcome::code).isEqualTo("INSUFFICIENT_STOCK");
            assertStockNeverNegative(store, label);
            assertThat(balance(store, "quantity")).as(label).isEqualByComparingTo("0");
            assertThat(balance(store, "reserved_quantity")).as(label).isEqualByComparingTo("0");
            assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", store)).as(label).isOne();
            assertThat(count("SELECT count(DISTINCT number) FROM sales WHERE tenant_id = ?", store)).as(label).isOne();
            assertThat(count("""
                    SELECT count(*) FROM sale_items i JOIN sales s ON s.id = i.sale_id WHERE s.tenant_id = ?
                    """, store)).as(label).isOne();
            assertThat(count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND type = 'out'", store))
                    .as(label).isOne();
            assertThat(count("SELECT count(*) FROM payments WHERE tenant_id = ?", store)).as(label).isOne();
            assertThat(count("""
                    SELECT count(*) FROM cash_movements WHERE tenant_id = ? AND reference_type = 'sale'
                    """, store)).as(label).isOne();
            assertNoOrphansOfRejectedSale(store, label);
        }
    }

    @Test
    void simultaneousPosSaleAndWebCheckoutOfTheLastUnitApproveExactlyOne() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            Store store = store();
            Cashier cashier = cashier(store, "CAJA-1");

            List<Outcome> outcomes = concurrently(() -> sell(store, cashier), () -> checkout(store));
            Outcome pos = outcomes.get(0);
            Outcome web = outcomes.get(1);

            String label = "escenario 2, ronda " + round + ": pos=" + pos + " web=" + web;
            boolean posWon = pos.status() == 201;
            boolean webWon = web.status() == 200;
            log.info("Prueba de oro, escenario 2, ronda {}: gano {} (pos={}, web={})",
                    round, posWon ? "la venta POS" : webWon ? "el checkout web" : "ninguno", pos.status(), web.status());
            assertThat(posWon ^ webWon).as(label + " (exactamente uno exitoso)").isTrue();
            Outcome loser = posWon ? web : pos;
            assertThat(loser.status()).as(label).isEqualTo(409);
            assertThat(loser.code()).as(label).isEqualTo("INSUFFICIENT_STOCK");
            assertStockNeverNegative(store, label);
            assertNoOrphansOfRejectedSale(store, label);
            long orphanItems = count("""
                    SELECT count(*) FROM order_items i WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = i.order_id)
                      AND i.product_id IN (SELECT id FROM products WHERE tenant_id = ?)
                    """, store);
            assertThat(orphanItems).as(label).isZero();

            if (posWon) {
                assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", store)).as(label).isOne();
                assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ?", store)).as(label).isZero();
                assertThat(count("SELECT count(*) FROM inventory_reservations WHERE tenant_id = ?", store))
                        .as(label).isZero();
                assertThat(count("SELECT count(*) FROM payments WHERE tenant_id = ? AND order_id IS NOT NULL", store))
                        .as(label).isZero();
                assertThat(balance(store, "quantity")).as(label).isEqualByComparingTo("0");
                assertThat(balance(store, "reserved_quantity")).as(label).isEqualByComparingTo("0");
            } else {
                assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", store)).as(label).isZero();
                assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ? AND status = 'confirmed'", store))
                        .as(label).isOne();
                assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ?", store)).as(label).isOne();
                assertThat(count("SELECT count(*) FROM inventory_reservations WHERE tenant_id = ?", store))
                        .as(label).isOne();
                assertThat(count("SELECT count(*) FROM payments WHERE tenant_id = ? AND sale_id IS NOT NULL", store))
                        .as(label).isZero();
                assertThat(count("SELECT count(*) FROM cash_movements WHERE tenant_id = ?", store)).as(label).isZero();
                assertThat(count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?", store))
                        .as(label).isZero();
                assertThat(balance(store, "quantity")).as(label).isEqualByComparingTo("1");
                assertThat(balance(store, "reserved_quantity")).as(label).isEqualByComparingTo("1");
            }
        }
    }

    /** Orden fijo (sin latch): la venta POS debe respetar la reserva que ya hizo el checkout web. */
    @Test
    void posSaleAfterWebCheckoutRespectsTheReservation() throws Exception {
        Store store = store();
        Cashier cashier = cashier(store, "CAJA-1");

        Outcome web = checkout(store);
        assertThat(web.status()).as("checkout web primero: " + web).isEqualTo(200);
        assertThat(balance(store, "reserved_quantity")).isEqualByComparingTo("1");

        Outcome pos = sell(store, cashier);
        assertThat(pos.status()).as("venta POS despues: " + pos).isEqualTo(409);
        assertThat(pos.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(balance(store, "quantity")).isEqualByComparingTo("1");
        assertThat(balance(store, "reserved_quantity")).isEqualByComparingTo("1");
        assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", store)).isZero();
        assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ? AND status = 'confirmed'", store)).isOne();
        assertThat(count("SELECT count(*) FROM inventory_reservations WHERE tenant_id = ?", store)).isOne();
        assertThat(count("SELECT count(*) FROM cash_movements WHERE tenant_id = ?", store)).isZero();
        assertStockNeverNegative(store, "orden fijo web -> POS");
        assertNoOrphansOfRejectedSale(store, "orden fijo web -> POS");
    }

    /** Orden fijo (sin latch): el checkout web no puede reservar la unidad que ya vendio el POS. */
    @Test
    void webCheckoutAfterPosSaleIsRejected() throws Exception {
        Store store = store();
        Cashier cashier = cashier(store, "CAJA-1");

        Outcome pos = sell(store, cashier);
        assertThat(pos.status()).as("venta POS primero: " + pos).isEqualTo(201);

        Outcome web = checkout(store);
        assertThat(web.status()).as("checkout web despues: " + web).isEqualTo(409);
        assertThat(web.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(balance(store, "quantity")).isEqualByComparingTo("0");
        assertThat(balance(store, "reserved_quantity")).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ?", store)).isZero();
        assertThat(count("SELECT count(*) FROM inventory_reservations WHERE tenant_id = ?", store)).isZero();
        assertThat(count("SELECT count(*) FROM sales WHERE tenant_id = ?", store)).isOne();
        assertStockNeverNegative(store, "orden fijo POS -> web");
        assertNoOrphansOfRejectedSale(store, "orden fijo POS -> web");
    }

    // ---------------------------------------------------------------- acciones HTTP

    private Outcome sell(Store store, Cashier cashier) throws Exception {
        String body = """
                {"branchId":"%s","cashShiftId":"%s",
                 "items":[{"productId":"%s","quantity":1}],
                 "payments":[{"method":"cash","amount":%s}],
                 "confirmationId":"%s"}
                """.formatted(store.branchId(), cashier.shiftId(), store.productId(), PRICE, UUID.randomUUID());
        return outcome(mvc.perform(post("/api/v1/pos/sales").header("Authorization", cashier.bearer())
                .contentType(APPLICATION_JSON).content(body)).andReturn());
    }

    /** Checkout web como invitado (sin JWT), con Idempotency-Key. */
    private Outcome checkout(Store store) throws Exception {
        String body = """
                {"items":[{"productId":"%s","quantity":1}],
                 "fullName":"María López","email":"maria@example.com","phone":"55551234",
                 "addressLine1":"7a Avenida #1","addressLine2":"","city":"Guatemala","department":"",
                 "references":"","cardholderName":"María López","cardLastFour":"4242"}
                """.formatted(store.productId());
        return outcome(mvc.perform(post("/api/v1/public/" + store.slug() + "/checkout")
                .header("Idempotency-Key", "golden-" + UUID.randomUUID())
                .contentType(APPLICATION_JSON).content(body)).andReturn());
    }

    private static Outcome outcome(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        String code = null;
        if (result.getResponse().getStatus() >= 400 && !body.isBlank()) {
            try {
                code = JsonPath.read(body, "$.code");
            } catch (RuntimeException ignored) {
                // Cuerpo sin "code": se reporta el cuerpo completo en la asercion.
            }
        }
        return new Outcome(result.getResponse().getStatus(), code, body);
    }

    /** Mismo patron que StorefrontCheckoutServiceConcurrencyTest: ambos salen a la vez y nada se cuelga. */
    private static List<Outcome> concurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = executor.submit(() -> awaitStartAndRun(first, ready, start));
            Future<Outcome> b = executor.submit(() -> awaitStartAndRun(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("las dos operaciones quedaron listas").isTrue();
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static Outcome awaitStartAndRun(Callable<Outcome> action, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Las operaciones no iniciaron.");
        try {
            return action.call();
        } catch (Exception exception) {
            // Una excepcion no manejada (500, deadlock...) se reporta como resultado para ver el detalle.
            return new Outcome(-1, exception.getClass().getSimpleName(), String.valueOf(exception.getMessage()));
        }
    }

    // ---------------------------------------------------------------- verificaciones en la base

    private void assertStockNeverNegative(Store store, String label) {
        assertThat(balance(store, "quantity")).as(label + " quantity >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balance(store, "quantity - reserved_quantity")).as(label + " disponible >= 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    /** Ningun pago, item, movimiento de caja o de inventario apunta a una venta que no existe. */
    private void assertNoOrphansOfRejectedSale(Store store, String label) {
        assertThat(count("""
                SELECT count(*) FROM payments p WHERE p.tenant_id = ? AND p.sale_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM sales s WHERE s.id = p.sale_id)
                """, store)).as(label + " pagos huerfanos").isZero();
        assertThat(count("""
                SELECT count(*) FROM cash_movements c WHERE c.tenant_id = ? AND c.reference_type = 'sale'
                  AND NOT EXISTS (SELECT 1 FROM sales s WHERE s.id = c.reference_id)
                """, store)).as(label + " movimientos de caja huerfanos").isZero();
        assertThat(count("""
                SELECT count(*) FROM inventory_movements m WHERE m.tenant_id = ? AND m.reference_type = 'POS_SALE'
                  AND NOT EXISTS (SELECT 1 FROM sales s WHERE s.id = m.reference_id)
                """, store)).as(label + " movimientos de inventario huerfanos").isZero();
        assertThat(count("""
                SELECT count(*) FROM payments p WHERE p.tenant_id = ? AND p.order_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = p.order_id)
                """, store)).as(label + " pagos de pedido huerfanos").isZero();
    }

    private BigDecimal balance(Store store, String expression) {
        return jdbc.queryForObject("SELECT " + expression + " FROM inventory_balances WHERE id = ?",
                BigDecimal.class, store.balanceId());
    }

    private long count(String sql, Store store) {
        return jdbc.queryForObject(sql, Long.class, store.tenantId());
    }

    // ---------------------------------------------------------------- datos (patron de JdbcTemplate del proyecto)

    /** Tienda con sucursal, e-commerce habilitado y un producto fisico con stock de 1 unidad. */
    private Store store() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        String slug = "golden-" + suffix;
        jdbc.update("INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')", tenantId, "Tienda " + suffix, slug);
        SubscriptionTestFixtures.provisionBasic(tenantSubscriptions, saasPlans, tenantId,
                List.of("ecommerce_delivery"));
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbc.update("INSERT INTO ecommerce_configs (tenant_id, enabled, store_name, require_account_for_checkout, "
                + "guest_tracking_enabled, allowed_delivery_methods, allowed_payment_methods, default_branch_id) "
                + "VALUES (?, true, ?, false, true, ARRAY['home_delivery'], ARRAY['card'], ?)",
                tenantId, "Tienda " + suffix, branchId);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoría " + suffix, "categoria-" + suffix);
        jdbc.update("INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                + "VALUES (?, ?, 'UND', 'Unidad', 'und', 'unit', false, 'active')", unitId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, sku, name, product_type, category_id, base_unit_id, sale_price, "
                + "status, tracking_stock, channel_pos, channel_ecommerce) "
                + "VALUES (?, ?, ?, ?, 'physical', ?, ?, " + PRICE + ", 'published', true, true, true)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        jdbc.update("INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, quantity, reserved_quantity) "
                + "VALUES (?, ?, ?, ?, 1.000, 0.000)", balanceId, tenantId, branchId, productId);
        return new Store(tenantId, branchId, productId, balanceId, slug);
    }

    /**
     * Empleado con rol activo (pos.sales.create y pos.cash.open) asignado a la sucursal, sesion real,
     * JWT real y su propio turno de caja abierto por HTTP.
     */
    private Cashier cashier(Store store, String registerCode) throws Exception {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, tenant_id, name, permissions, branch_scope, status) "
                + "VALUES (?, ?, ?, ARRAY['pos.sales.create', 'pos.cash.open'], 'assigned', 'active')",
                roleId, store.tenantId(), "Cajero " + registerCode + " " + userId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id, allowed_branch_ids) "
                + "VALUES (?, ?, ?, ?, 'employee', 'active', ?, ?, CAST(? AS uuid[]))",
                userId, store.tenantId(), "Cajero " + registerCode, userId + "@golden.test", roleId,
                store.branchId(), "{" + store.branchId() + "}");
        jdbc.update("INSERT INTO sessions (id, user_id, active_branch_id, remember_me, expires_at) "
                + "VALUES (?, ?, ?, false, date_trunc('second', now() + interval '1 hour'))",
                sessionId, userId, store.branchId());
        User user = userRepository.findById(userId).orElseThrow();
        Session session = sessionRepository.findById(sessionId).orElseThrow();
        String bearer = "Bearer " + jwtService.generateToken(user, session);

        int status = mvc.perform(post("/api/v1/pos/cash-shifts/open").header("Authorization", bearer)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","registerCode":"%s","openingAmount":100.00}
                                """.formatted(store.branchId(), registerCode)))
                .andReturn().getResponse().getStatus();
        assertThat(status).as("apertura de turno de " + registerCode).isEqualTo(201);
        UUID shiftId = jdbc.queryForObject(
                "SELECT id FROM cash_shifts WHERE tenant_id = ? AND user_id = ? AND status = 'open'",
                UUID.class, store.tenantId(), userId);
        return new Cashier(bearer, shiftId);
    }

    private record Store(UUID tenantId, UUID branchId, UUID productId, UUID balanceId, String slug) {}

    private record Cashier(String bearer, UUID shiftId) {}

    private record Outcome(int status, String code, String body) {
        @Override
        public String toString() {
            return status + (code != null ? " " + code : "") + (status >= 400 || status < 0 ? " " + body : "");
        }
    }
}
