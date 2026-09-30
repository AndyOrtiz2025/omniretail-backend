package com.omniretail.backend.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.purchasing.dto.CreateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.dto.CreateReceiptIncidentRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptItemRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptResponse;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GoodsReceiptServiceConcurrencyTest {

    @Autowired private GoodsReceiptService service;
    @Autowired private ReceiptIncidentService incidentService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(permissions.hasPermission(any(), any(), eq("receiving.receipts.create")))
                .willReturn(true);
        given(permissions.hasPermission(any(), any(), eq("receiving.receipts.confirm")))
                .willReturn(true);
        given(permissions.hasPermission(any(), any(), eq("receiving.incidents.manage")))
                .willReturn(true);
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void concurrentReceiptsForSamePoSerializeAndOnlyOneCanOverReceive() throws Exception {
        Fixture fixture = fixture();
        GoodsReceiptResponse first = create(fixture, "7");
        GoodsReceiptResponse second = create(fixture, "7");

        List<Outcome> outcomes = race(first.id(), second.id());

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.success())
                .singleElement()
                .extracting(Outcome::code)
                .isEqualTo("GOODS_RECEIPT_OVER_RECEIVING");
        assertThat(confirmedTotal(fixture)).isEqualByComparingTo("7");
        assertThat(balance(fixture)).isEqualByComparingTo("7");
        assertThat(movementCount(fixture)).isOne();
        assertThat(balanceCount(fixture)).isOne();
        assertThat(orderStatus(fixture)).isEqualTo("partially_received");
    }

    @Test
    void concurrentDoubleConfirmOnlyIncrementsStockOnce() throws Exception {
        Fixture fixture = fixture();
        GoodsReceiptResponse receipt = create(fixture, "10");

        List<Outcome> outcomes = race(receipt.id(), receipt.id());

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.success())
                .singleElement()
                .extracting(Outcome::code)
                .isEqualTo("GOODS_RECEIPT_INVALID_STATUS");
        assertThat(confirmedTotal(fixture)).isEqualByComparingTo("10");
        assertThat(balance(fixture)).isEqualByComparingTo("10");
        assertThat(movementCount(fixture)).isOne();
        assertThat(orderStatus(fixture)).isEqualTo("received");
    }

    @Test
    void incidentServiceRequiresManagePermissionWithoutController() {
        Fixture fixture = fixture();
        GoodsReceiptResponse receipt = create(fixture, "10");
        given(permissions.hasPermission(any(), any(), eq("receiving.incidents.manage")))
                .willReturn(false);

        assertThatThrownBy(() -> incidentService.create(
                        receipt.id(),
                        new CreateReceiptIncidentRequest(
                                ReceiptIncidentType.other, null, null, "Sin permiso")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ACCESS_DENIED"));
        assertThat(openIncidentCount(fixture, receipt.id())).isZero();
    }

    @Test
    void concurrentIncidentCreationAndConfirmationSerializeOnReceipt() throws Exception {
        Fixture fixture = fixture();
        GoodsReceiptResponse receipt = create(fixture, "10");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TimedOutcome> createIncident = executor.submit(
                    () -> createIncident(receipt.id(), ready, start));
            Future<TimedOutcome> confirmReceipt = executor.submit(
                    () -> confirmTimed(receipt.id(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            TimedOutcome incidentOutcome = createIncident.get(30, TimeUnit.SECONDS);
            TimedOutcome confirmOutcome = confirmReceipt.get(30, TimeUnit.SECONDS);
            assertThat(incidentOutcome.success()).isTrue();
            assertThat(openIncidentCount(fixture, receipt.id())).isOne();
            assertThat(movementCount(fixture)).isLessThanOrEqualTo(1L);
            assertThat(balanceCount(fixture)).isLessThanOrEqualTo(1L);

            if (receiptStatus(receipt.id()).equals("confirmed")) {
                assertThat(confirmOutcome.success()).isTrue();
                assertThat(incidentOutcome.completedAtNanos())
                        .isGreaterThanOrEqualTo(confirmOutcome.completedAtNanos());
                assertThat(balance(fixture)).isEqualByComparingTo("10");
                assertThat(movementCount(fixture)).isOne();
                assertThat(balanceCount(fixture)).isOne();
            } else {
                assertThat(confirmOutcome.success()).isFalse();
                assertThat(confirmOutcome.code()).isEqualTo("RECEIPT_HAS_OPEN_INCIDENTS");
                assertThat(incidentOutcome.completedAtNanos())
                        .isLessThanOrEqualTo(confirmOutcome.completedAtNanos());
                assertThat(receiptStatus(receipt.id())).isEqualTo("draft");
                assertThat(movementCount(fixture)).isZero();
                assertThat(balanceCount(fixture)).isZero();
                assertThat(orderStatus(fixture)).isEqualTo("approved");
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private List<Outcome> race(UUID firstId, UUID secondId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> confirm(firstId, ready, start));
            Future<Outcome> second = executor.submit(() -> confirm(secondId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Outcome confirm(UUID receiptId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las confirmaciones no iniciaron a tiempo.");
        }
        try {
            service.confirm(receiptId);
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private TimedOutcome createIncident(
            UUID receiptId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        awaitRaceStart(ready, start);
        try {
            incidentService.create(
                    receiptId,
                    new CreateReceiptIncidentRequest(
                            ReceiptIncidentType.other, null, null, "Incidencia concurrente"));
            return new TimedOutcome(true, null, System.nanoTime());
        } catch (BusinessException exception) {
            return new TimedOutcome(false, exception.getCode(), System.nanoTime());
        }
    }

    private TimedOutcome confirmTimed(
            UUID receiptId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        awaitRaceStart(ready, start);
        try {
            service.confirm(receiptId);
            return new TimedOutcome(true, null, System.nanoTime());
        } catch (BusinessException exception) {
            return new TimedOutcome(false, exception.getCode(), System.nanoTime());
        }
    }

    private static void awaitRaceStart(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las operaciones concurrentes no iniciaron a tiempo.");
        }
    }

    private GoodsReceiptResponse create(Fixture fixture, String quantity) {
        return service.create(new CreateGoodsReceiptRequest(
                fixture.order(),
                null,
                List.of(new GoodsReceiptItemRequest(
                        fixture.orderItem(), new BigDecimal(quantity), fixture.location()))));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID supplierProduct = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID orderItem = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                user, tenant, UserType.employee, role, branch, UUID.randomUUID()));

        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Receiving race', ?)", tenant, "race-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO roles (id, tenant_id, name, branch_scope, permissions)
                VALUES (?, ?, ?, 'all', '{}')
                """, role, tenant, "Rol " + role);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id)
                VALUES (?, ?, 'Receptor', ?, 'employee', 'active', ?, ?)
                """, user, tenant, user + "@test.local", role, branch);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, tracking_stock)
                VALUES (?, ?, ?, 'Producto', ?, ?, true)
                """, product, tenant, "SKU-" + product, category, unit);
        jdbc.update("INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, 'Proveedor', 'active')",
                supplier, tenant);
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, ?, 1, 1, 0, 1)
                """, supplierProduct, tenant, supplier, product, unit);
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, branch_id, number, supplier_id, supplier_name_snapshot,
                     status, subtotal, total, created_by_user_id)
                VALUES (?, ?, ?, 'OC-RACE', ?, 'Proveedor', 'approved', 10, 10, ?)
                """, order, tenant, branch, supplier, user);
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, ?, 'Producto', 'SKU', 10, ?, 'u', 1, 1, 10)
                """, orderItem, tenant, order, supplierProduct, product, unit);
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')
                """, location, tenant, branch, "L-" + location.toString().substring(0, 8));
        return new Fixture(tenant, branch, order, orderItem, product, location);
    }

    private BigDecimal confirmedTotal(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(i.received_quantity), 0)
                FROM goods_receipt_items i
                JOIN goods_receipts r ON r.id = i.goods_receipt_id
                WHERE r.tenant_id = ? AND r.purchase_order_id = ? AND r.status = 'confirmed'
                """, BigDecimal.class, fixture.tenant(), fixture.order());
    }

    private BigDecimal balance(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """, BigDecimal.class, fixture.tenant(), fixture.branch(), fixture.product(), fixture.location());
    }

    private long movementCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND reference_type = 'goods_receipt'",
                Long.class,
                fixture.tenant());
    }

    private long balanceCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_balances WHERE tenant_id = ?",
                Long.class,
                fixture.tenant());
    }

    private long openIncidentCount(Fixture fixture, UUID receiptId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM receipt_incidents
                WHERE tenant_id = ? AND goods_receipt_id = ? AND status = 'open'
                """, Long.class, fixture.tenant(), receiptId);
    }

    private String receiptStatus(UUID receiptId) {
        return jdbc.queryForObject(
                "SELECT status FROM goods_receipts WHERE id = ?", String.class, receiptId);
    }

    private String orderStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM purchase_orders WHERE id = ?", String.class, fixture.order());
    }

    private record Outcome(boolean success, String code) {}

    private record TimedOutcome(boolean success, String code, long completedAtNanos) {}

    private record Fixture(
            UUID tenant, UUID branch, UUID order, UUID orderItem, UUID product, UUID location) {}
}
