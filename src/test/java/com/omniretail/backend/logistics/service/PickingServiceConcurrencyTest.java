package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.OrderAdminService;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.service.OrderEmailNotifier;
import com.omniretail.backend.logistics.dto.PickingActionResponse;
import com.omniretail.backend.logistics.dto.PickingLineResponse;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class PickingServiceConcurrencyTest {

    @Autowired private PickingService service;
    @Autowired private OrderAdminService orderAdminService;
    @Autowired private OrderFulfillmentConsumptionService fulfillmentConsumption;
    @Autowired private PickingOrderRepository pickingOrders;
    @Autowired private PickingItemRepository pickingItems;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private OrderEmailNotifier orderEmailNotifier;

    @Test
    void concurrentRetriesApplyThePickingMutationExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(),
                fixture.tenantId(),
                UserType.employee,
                null,
                fixture.branchId(),
                UUID.randomUUID()));
        UUID pickingId = service.ensureForOrder(fixture.tenantId(), fixture.orderId())
                .orElseThrow()
                .getId();
        service.assign(fixture.branchId(), pickingId);
        UUID itemId = pickingItems.findByTenantIdAndPickingOrderId(fixture.tenantId(), pickingId)
                .getFirst()
                .getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PickingLineResponse> first = executor.submit(
                    () -> update(fixture, pickingId, itemId, ready, start));
            Future<PickingLineResponse> second = executor.submit(
                    () -> update(fixture, pickingId, itemId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<PickingLineResponse> responses = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses)
                    .allSatisfy(response ->
                            assertThat(response.pickedQuantity()).isEqualByComparingTo("2.000"));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM picking_item_update_operations WHERE tenant_id = ?",
                    Long.class,
                    fixture.tenantId()))
                    .isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT picked_quantity FROM picking_items WHERE id = ?",
                    BigDecimal.class,
                    itemId))
                    .isEqualByComparingTo("2.000");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCompletionCreatesOneCanonicalPacking() throws Exception {
        Fixture fixture = fixture();
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
        UUID pickingId = service.ensureForOrder(fixture.tenantId(), fixture.orderId())
                .orElseThrow()
                .getId();
        service.assign(fixture.branchId(), pickingId);
        UUID itemId = pickingItems.findByTenantIdAndPickingOrderId(fixture.tenantId(), pickingId)
                .getFirst()
                .getId();
        service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("5.000"), null, "complete-item"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PickingActionResponse> first = executor.submit(
                    () -> complete(fixture, pickingId, ready, start));
            Future<PickingActionResponse> second = executor.submit(
                    () -> complete(fixture, pickingId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .extracting(PickingActionResponse::idempotent)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM packings WHERE tenant_id = ? AND picking_order_id = ?",
                    Long.class,
                    fixture.tenantId(),
                    pickingId))
                    .isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCancellationAndAssignmentLeaveNoOperationalPicking() throws Exception {
        Fixture fixture = fixture();
        allow(fixture);
        UUID pickingId = service.ensureForOrder(fixture.tenantId(), fixture.orderId())
                .orElseThrow()
                .getId();

        runConcurrently(
                () -> orderAdminService.updateStatus(fixture.orderId(), OrderStatus.cancelled),
                () -> service.assign(fixture.branchId(), pickingId));

        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.cancelled.name());
        assertThat(pickingOrders.findById(pickingId).orElseThrow().getStatus())
                .isEqualTo(PickingStatus.cancelled);
    }

    @Test
    void concurrentCancellationAndItemUpdateLeaveNoOperationalPicking() throws Exception {
        Fixture fixture = fixture();
        allow(fixture);
        UUID pickingId = service.ensureForOrder(fixture.tenantId(), fixture.orderId())
                .orElseThrow()
                .getId();
        service.assign(fixture.branchId(), pickingId);
        UUID itemId = pickingItems.findByTenantIdAndPickingOrderId(fixture.tenantId(), pickingId)
                .getFirst()
                .getId();

        runConcurrently(
                () -> orderAdminService.updateStatus(fixture.orderId(), OrderStatus.cancelled),
                () -> service.updateItem(
                        fixture.branchId(),
                        pickingId,
                        itemId,
                        new UpdatePickingItemRequest(
                                new BigDecimal("2.000"), null, "cancel-concurrent-update")));

        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.cancelled.name());
        assertThat(pickingOrders.findById(pickingId).orElseThrow().getStatus())
                .isEqualTo(PickingStatus.cancelled);
    }

    @Test
    void concurrentPickingCreationAndCancellationLeaveNoOperationalPicking() throws Exception {
        Fixture fixture = fixture();
        allow(fixture);

        runConcurrently(
                () -> orderAdminService.updateStatus(fixture.orderId(), OrderStatus.cancelled),
                () -> service.ensureForOrder(fixture.tenantId(), fixture.orderId()));

        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.cancelled.name());
        pickingOrders.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), PickingSourceType.order, fixture.orderId())
                .ifPresent(picking -> assertThat(picking.getStatus()).isEqualTo(PickingStatus.cancelled));
    }

    @Test
    void concurrentCancellationsReleaseCrossedPhysicalSelectionsInGlobalLockOrder()
            throws Exception {
        TraceCancellationFixture trace = traceCancellationFixture();
        allow(trace.fixture());
        List<String> historicalTraces = pickedTraces(trace);

        runConcurrentlyExpectingSuccess(
                () -> orderAdminService.updateStatus(trace.fixture().orderId(), OrderStatus.cancelled),
                () -> orderAdminService.updateStatus(trace.secondOrderId(), OrderStatus.cancelled));

        assertThat(jdbc.queryForList(
                        "SELECT status FROM orders WHERE id IN (?, ?) ORDER BY id",
                        String.class,
                        trace.fixture().orderId(),
                        trace.secondOrderId()))
                .containsExactly(OrderStatus.cancelled.name(), OrderStatus.cancelled.name());
        assertThat(jdbc.queryForList(
                        "SELECT status FROM picking_orders WHERE id IN (?, ?) ORDER BY id",
                        String.class,
                        trace.firstPickingId(),
                        trace.secondPickingId()))
                .containsExactly(PickingStatus.cancelled.name(), PickingStatus.cancelled.name());
        assertThat(jdbc.queryForList(
                        "SELECT reserved_quantity FROM inventory_lot_balances "
                                + "WHERE lot_id IN (?, ?) ORDER BY lot_id",
                        BigDecimal.class,
                        trace.firstLotId(),
                        trace.secondLotId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(jdbc.queryForList(
                        "SELECT reserved_quantity FROM inventory_balances "
                                + "WHERE id IN (?, ?) ORDER BY id",
                        BigDecimal.class,
                        trace.fixture().balanceId(),
                        trace.secondBalanceId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_reservations "
                                + "WHERE source_id IN (?, ?) AND status = 'released'",
                        Long.class,
                        trace.fixture().orderId(),
                        trace.secondOrderId()))
                .isEqualTo(4L);
        assertThat(pickedTraces(trace)).containsExactlyElementsOf(historicalTraces);
    }

    @Test
    void cancellationAndFulfillmentConsumptionShareTheGlobalInventoryLockOrder()
            throws Exception {
        TraceCancellationFixture trace = traceCancellationFixture();
        allow(trace.fixture());
        List<String> historicalTraces = pickedTraces(trace);
        jdbc.update(
                "UPDATE picking_orders SET status = 'completed', completed_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                trace.secondPickingId());

        runConcurrentlyExpectingSuccess(
                () -> orderAdminService.updateStatus(
                        trace.fixture().orderId(), OrderStatus.cancelled),
                () -> {
                    fulfillmentConsumption.consumeOrder(
                            trace.fixture().tenantId(),
                            trace.fixture().branchId(),
                            trace.secondOrderId(),
                            trace.fixture().userId(),
                            "Consumo concurrente",
                            "dispatch",
                            UUID.randomUUID());
                    return null;
                });

        assertThat(orderStatus(trace.fixture())).isEqualTo(OrderStatus.cancelled.name());
        assertThat(jdbc.queryForList(
                        "SELECT status FROM inventory_reservations "
                                + "WHERE source_id IN (?, ?) ORDER BY source_id, product_id",
                        String.class,
                        trace.fixture().orderId(),
                        trace.secondOrderId()))
                .containsExactlyInAnyOrder("released", "released", "consumed", "consumed");
        assertThat(jdbc.queryForList(
                        "SELECT quantity FROM inventory_balances WHERE id IN (?, ?) ORDER BY id",
                        BigDecimal.class,
                        trace.fixture().balanceId(),
                        trace.secondBalanceId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo("15.000"));
        assertThat(jdbc.queryForList(
                        "SELECT reserved_quantity FROM inventory_balances "
                                + "WHERE id IN (?, ?) ORDER BY id",
                        BigDecimal.class,
                        trace.fixture().balanceId(),
                        trace.secondBalanceId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(jdbc.queryForList(
                        "SELECT quantity FROM inventory_lot_balances "
                                + "WHERE lot_id IN (?, ?) ORDER BY lot_id",
                        BigDecimal.class,
                        trace.firstLotId(),
                        trace.secondLotId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo("15.000"));
        assertThat(jdbc.queryForList(
                        "SELECT reserved_quantity FROM inventory_lot_balances "
                                + "WHERE lot_id IN (?, ?) ORDER BY lot_id",
                        BigDecimal.class,
                        trace.firstLotId(),
                        trace.secondLotId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(pickedTraces(trace)).containsExactlyElementsOf(historicalTraces);
    }

    @Test
    void concurrentNonTraceableCancellationsReleaseCrossedSharedBalances()
            throws Exception {
        TraceCancellationFixture trace = traceCancellationFixture();
        allow(trace.fixture());
        jdbc.update(
                "UPDATE products SET tracking_lot = false WHERE tenant_id = ?",
                trace.fixture().tenantId());
        jdbc.update(
                "UPDATE picking_items SET picked_traces = NULL, picked_quantity = 0, "
                        + "location_id = NULL, status = 'pending' "
                        + "WHERE picking_order_id IN (?, ?)",
                trace.firstPickingId(),
                trace.secondPickingId());

        runConcurrentlyExpectingSuccess(
                () -> orderAdminService.updateStatus(
                        trace.fixture().orderId(), OrderStatus.cancelled),
                () -> orderAdminService.updateStatus(
                        trace.secondOrderId(), OrderStatus.cancelled));

        assertThat(jdbc.queryForList(
                        "SELECT reserved_quantity FROM inventory_balances "
                                + "WHERE id IN (?, ?) ORDER BY id",
                        BigDecimal.class,
                        trace.fixture().balanceId(),
                        trace.secondBalanceId()))
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_reservations "
                                + "WHERE source_id IN (?, ?) AND status = 'released'",
                        Long.class,
                        trace.fixture().orderId(),
                        trace.secondOrderId()))
                .isEqualTo(4L);
    }

    private void allow(Fixture fixture) {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(),
                fixture.tenantId(),
                UserType.employee,
                null,
                fixture.branchId(),
                UUID.randomUUID()));
    }

    private void runConcurrently(ThrowingAction firstAction, ThrowingAction secondAction)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runAfterBarrier(firstAction, ready, start));
            Future<?> second = executor.submit(() -> runAfterBarrier(secondAction, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            awaitBusinessOutcome(first);
            awaitBusinessOutcome(second);
        } finally {
            executor.shutdownNow();
        }
    }

    private void runConcurrentlyExpectingSuccess(
            ThrowingAction firstAction, ThrowingAction secondAction) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runAfterBarrier(firstAction, ready, start));
            Future<?> second = executor.submit(() -> runAfterBarrier(secondAction, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private Object runAfterBarrier(
            ThrowingAction action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las operaciones concurrentes no iniciaron a tiempo.");
        }
        return action.run();
    }

    private void awaitBusinessOutcome(Future<?> outcome) throws Exception {
        try {
            outcome.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOf(BusinessException.class);
        }
    }

    private String orderStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId());
    }

    private PickingLineResponse update(
            Fixture fixture,
            UUID pickingId,
            UUID itemId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las actualizaciones de Picking no iniciaron a tiempo.");
        }
        return service.updateItem(
                fixture.branchId(),
                pickingId,
                itemId,
                new UpdatePickingItemRequest(
                        new BigDecimal("2.000"), null, "concurrent-operation"));
    }

    private PickingActionResponse complete(
            Fixture fixture,
            UUID pickingId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las finalizaciones de Picking no iniciaron a tiempo.");
        }
        return service.complete(fixture.branchId(), pickingId);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID orderItem = UUID.randomUUID();
        UUID balance = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Picking concurrency', ?)",
                tenant, "pick-concurrency-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "BR-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Picker', ?, 'employee', 'active', ?)
                """, user, tenant, user + "@test.local", branch);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', 'active')
                """, unit, tenant, "U-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, true, false, false, false)
                """, product, tenant, "SKU-" + suffix, category, unit);
        jdbc.update("""
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                     total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{"name":"Cliente"}'::jsonb, 'confirmed',
                        'home_delivery', 'third_party', 50, 0, 0, 50, ?)
                """, order, tenant, branch, "WEB-" + order, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                     inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, 'SKU', 'Producto', 5, 5, 10, 0, 50)
                """, orderItem, order, product);
        jdbc.update("""
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, 10, 5)
                """, balance, tenant, branch, product);
        String allocations = """
                [{"id":"%s","balanceId":"%s","locationId":null,
                  "reservedQuantity":5,"consumedQuantity":0}]
                """.formatted(UUID.randomUUID(), balance);
        jdbc.update("""
                INSERT INTO inventory_reservations
                    (tenant_id, branch_id, source_type, source_id, source_line_id,
                     order_id, order_item_id, product_id, quantity, status, allocations)
                VALUES (?, ?, 'order', ?, ?, ?, ?, ?, 5, 'active', ?::jsonb)
                """, tenant, branch, order, orderItem, order, orderItem, product, allocations);
        return new Fixture(tenant, branch, user, product, order, orderItem, balance);
    }

    private TraceCancellationFixture traceCancellationFixture() {
        Fixture fixture = fixture();
        UUID secondProduct = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        UUID firstOrderSecondItem = UUID.randomUUID();
        UUID secondOrderFirstItem = UUID.randomUUID();
        UUID secondOrderSecondItem = UUID.randomUUID();
        UUID secondBalance = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID firstLot = UUID.randomUUID();
        UUID secondLot = UUID.randomUUID();
        UUID firstPicking = UUID.randomUUID();
        UUID secondPicking = UUID.randomUUID();

        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.productId());
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                SELECT ?, tenant_id, ?, 'Producto trazable B', product_type, category_id,
                       base_unit_id, true, true, false, false
                FROM products WHERE id = ?
                """, secondProduct, "SKU-B-" + fixture.tenantId(), fixture.productId());
        jdbc.update("UPDATE inventory_balances SET quantity = 20, reserved_quantity = 10 WHERE id = ?",
                fixture.balanceId());
        jdbc.update("""
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, 20, 10)
                """, secondBalance, fixture.tenantId(), fixture.branchId(), secondProduct);
        jdbc.update("""
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                     total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{"name":"Cliente B"}'::jsonb, 'confirmed',
                        'home_delivery', 'third_party', 100, 0, 0, 100, ?)
                """, secondOrder, fixture.tenantId(), fixture.branchId(),
                "WEB-" + secondOrder, UUID.randomUUID().toString());

        insertOrderItem(firstOrderSecondItem, fixture.orderId(), secondProduct, "SKU-B");
        insertOrderItem(secondOrderFirstItem, secondOrder, secondProduct, "SKU-B");
        insertOrderItem(secondOrderSecondItem, secondOrder, fixture.productId(), "SKU-A");
        insertReservation(
                fixture, fixture.orderId(), firstOrderSecondItem, secondProduct, secondBalance);
        insertReservation(
                fixture, secondOrder, secondOrderFirstItem, secondProduct, secondBalance);
        insertReservation(
                fixture, secondOrder, secondOrderSecondItem, fixture.productId(), fixture.balanceId());

        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Trazabilidad concurrente', 'warehouse', 'active')
                """, location, fixture.tenantId(), fixture.branchId(),
                "TRACE-" + fixture.tenantId().toString().substring(0, 8));
        insertLotAndBalance(fixture, firstLot, fixture.productId(), location, "LOT-A");
        insertLotAndBalance(fixture, secondLot, secondProduct, location, "LOT-B");
        insertPicking(fixture, firstPicking, fixture.orderId());
        insertPicking(fixture, secondPicking, secondOrder);

        // El orden historico de lineas es opuesto: A->B en el primer pedido y B->A en el segundo.
        insertPickingItem(
                fixture, firstPicking, fixture.orderItemId(), fixture.productId(), location, firstLot);
        insertPickingItem(
                fixture, firstPicking, firstOrderSecondItem, secondProduct, location, secondLot);
        insertPickingItem(
                fixture, secondPicking, secondOrderFirstItem, secondProduct, location, secondLot);
        insertPickingItem(
                fixture, secondPicking, secondOrderSecondItem, fixture.productId(), location, firstLot);

        return new TraceCancellationFixture(
                fixture,
                secondOrder,
                firstPicking,
                secondPicking,
                secondBalance,
                firstLot,
                secondLot);
    }

    private void insertOrderItem(UUID id, UUID orderId, UUID productId, String sku) {
        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                     inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, ?, 'Producto trazable', 5, 5, 10, 0, 50)
                """, id, orderId, productId, sku);
    }

    private void insertReservation(
            Fixture fixture, UUID orderId, UUID orderItemId, UUID productId, UUID balanceId) {
        String allocations = """
                [{"id":"%s","balanceId":"%s","locationId":null,
                  "reservedQuantity":5,"consumedQuantity":0}]
                """.formatted(UUID.randomUUID(), balanceId);
        jdbc.update("""
                INSERT INTO inventory_reservations
                    (tenant_id, branch_id, source_type, source_id, source_line_id,
                     order_id, order_item_id, product_id, quantity, status, allocations)
                VALUES (?, ?, 'order', ?, ?, ?, ?, ?, 5, 'active', ?::jsonb)
                """, fixture.tenantId(), fixture.branchId(), orderId, orderItemId,
                orderId, orderItemId, productId, allocations);
    }

    private void insertLotAndBalance(
            Fixture fixture, UUID lotId, UUID productId, UUID locationId, String lotNumber) {
        jdbc.update("""
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, ?)
                """, lotId, fixture.tenantId(), productId, lotNumber);
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 20, 10)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId, lotId);
    }

    private void insertPicking(Fixture fixture, UUID pickingId, UUID orderId) {
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, order_id,
                     assigned_user_id, status, priority)
                VALUES (?, ?, ?, 'order', ?, ?, ?, 'in_progress', 'normal')
                """, pickingId, fixture.tenantId(), fixture.branchId(), orderId, orderId,
                fixture.userId());
    }

    private void insertPickingItem(
            Fixture fixture,
            UUID pickingId,
            UUID orderItemId,
            UUID productId,
            UUID locationId,
            UUID lotId) {
        String pickedTraces = """
                [{"locationId":"%s","lotId":"%s","quantity":5.000,"serialNumbers":[]}]
                """.formatted(locationId, lotId);
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id, product_id,
                     requested_quantity, picked_quantity, location_id, status, picked_traces)
                VALUES (?, ?, ?, ?, ?, ?, 5, 5, ?, 'completed', ?::jsonb)
                """, UUID.randomUUID(), fixture.tenantId(), pickingId, orderItemId, orderItemId,
                productId, locationId, pickedTraces);
    }

    private List<String> pickedTraces(TraceCancellationFixture trace) {
        return jdbc.queryForList(
                "SELECT picked_traces::text FROM picking_items "
                        + "WHERE picking_order_id IN (?, ?) ORDER BY id",
                String.class,
                trace.firstPickingId(),
                trace.secondPickingId());
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID userId,
            UUID productId,
            UUID orderId,
            UUID orderItemId,
            UUID balanceId) {}

    private record TraceCancellationFixture(
            Fixture fixture,
            UUID secondOrderId,
            UUID firstPickingId,
            UUID secondPickingId,
            UUID secondBalanceId,
            UUID firstLotId,
            UUID secondLotId) {}

    @FunctionalInterface
    private interface ThrowingAction {
        Object run() throws Exception;
    }
}
