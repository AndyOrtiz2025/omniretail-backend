package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.logistics.dto.PickingLineResponse;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
    @Autowired private PickingOrderRepository pickingOrders;
    @Autowired private PickingItemRepository pickingItems;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

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
        return new Fixture(tenant, branch, user, order);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID userId, UUID orderId) {}
}
