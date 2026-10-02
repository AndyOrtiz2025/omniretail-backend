package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.logistics.dto.PackingActionResponse;
import com.omniretail.backend.logistics.dto.PackingChecklistRequest;
import com.omniretail.backend.logistics.dto.PackingDetailResponse;
import com.omniretail.backend.logistics.dto.PackingFinalizeResponse;
import com.omniretail.backend.logistics.dto.PackingVersionedRequest;
import com.omniretail.backend.logistics.dto.RegisterPackingLabelPrintRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PackingServiceTest {

    @Autowired private PackingService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowBranch() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void readsOnlyTheAuthenticatedTenantAndSelectedBranch() {
        Fixture fixture = fixture();
        actor(fixture);

        assertThat(service.getQueue(fixture.branchId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.packingId()).isEqualTo(fixture.packingId());
                    assertThat(item.customerName()).isEqualTo("Cliente Packing");
                    assertThat(item.version()).isZero();
                });
        assertThat(service.getDetail(fixture.branchId(), fixture.packingId()).preparedContents())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.productId()).isEqualTo(fixture.productId());
                    assertThat(item.quantity()).isEqualByComparingTo("5.000");
                    assertThat(item.serialNumbers()).isEmpty();
                });

        assertCode(
                () -> service.getDetail(UUID.randomUUID(), fixture.packingId()),
                "PACKING_NOT_FOUND");
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
        assertCode(
                () -> service.getDetail(fixture.branchId(), fixture.packingId()),
                "PACKING_NOT_FOUND");
    }

    @Test
    void executesPreparationLabelAndFinalizeWithoutTouchingInventory() {
        Fixture fixture = fixture();
        actor(fixture);

        PackingActionResponse prepared = service.savePreparation(
                fixture.branchId(), fixture.packingId(), preparation(0L, "prepare", "2.750", 2));
        PackingActionResponse labeled = service.generateLabel(
                fixture.branchId(), fixture.packingId(), versioned(1L, "label"));
        PackingActionResponse printed = service.registerLabelPrint(
                fixture.branchId(),
                fixture.packingId(),
                new RegisterPackingLabelPrintRequest(
                        2L, "print", labeled.packing().labelGenerationId()));
        PackingFinalizeResponse finalized = service.finalizePacking(
                fixture.branchId(), fixture.packingId(), versioned(3L, "finalize"));
        PackingFinalizeResponse finalizeRetry = service.finalizePacking(
                fixture.branchId(), fixture.packingId(), versioned(3L, "finalize"));

        assertThat(prepared.packing().version()).isEqualTo(1L);
        assertThat(labeled.packing().labelCode()).isEqualTo("LBL-" + fixture.orderNumber() + "-2");
        assertThat(printed.packing().labelPrintedAt()).isNotNull();
        assertThat(finalized.packing().status().name()).isEqualTo("finalized");
        assertThat(finalized.orderStatus()).isEqualTo(OrderStatus.ready_for_dispatch);
        assertThat(finalizeRetry.idempotent()).isTrue();
        assertPackingSnapshot(finalizeRetry.packing(), finalized.packing());
        assertThat(physicalQuantity(fixture)).isEqualByComparingTo("10.000");
        assertThat(reservedQuantity(fixture)).isEqualByComparingTo("5.000");
        assertThat(reservationStatus(fixture)).isEqualTo(InventoryReservationStatus.active.name());
        assertThat(movementCount(fixture)).isZero();
    }

    @Test
    void historicalRetryReturnsItsOriginalSnapshotBeforeCheckingStaleVersion() {
        Fixture fixture = fixture();
        actor(fixture);
        SavePackingPreparationRequest original = preparation(0L, "historic", "2.000", 1);

        PackingActionResponse first = service.savePreparation(
                fixture.branchId(), fixture.packingId(), original);
        service.savePreparation(
                fixture.branchId(), fixture.packingId(), preparation(1L, "later", "4.000", 2));
        PackingActionResponse retry = service.savePreparation(
                fixture.branchId(), fixture.packingId(), original);

        assertThat(retry.idempotent()).isTrue();
        assertPackingSnapshot(retry.packing(), first.packing());
        assertThat(retry.packing().totalWeight()).isEqualByComparingTo("2.000");
        assertThat(service.getDetail(fixture.branchId(), fixture.packingId()).totalWeight())
                .isEqualByComparingTo("4.000");
        assertThat(operationCount(fixture)).isEqualTo(2);
    }

    @Test
    void rejectsStaleVersion() {
        Fixture fixture = fixture();
        actor(fixture);
        service.savePreparation(
                fixture.branchId(), fixture.packingId(), preparation(0L, "first", "2.000", 1));

        assertCode(
                () -> service.savePreparation(
                        fixture.branchId(), fixture.packingId(), preparation(0L, "stale", "3.000", 1)),
                "PACKING_VERSION_CONFLICT");
    }

    @Test
    void rejectsOperationIdWithAnotherFingerprint() {
        Fixture fixture = fixture();
        actor(fixture);
        service.savePreparation(
                fixture.branchId(), fixture.packingId(), preparation(0L, "reused", "2.000", 1));

        assertCode(
                () -> service.savePreparation(
                        fixture.branchId(), fixture.packingId(), preparation(0L, "reused", "3.000", 1)),
                "PACKING_OPERATION_ID_REUSED");
    }

    @Test
    void rejectsFinalizeUntilChecklistMeasurementsAndPrintedLabelExist() {
        Fixture fixture = fixture();
        actor(fixture);

        assertCode(
                () -> service.finalizePacking(
                        fixture.branchId(), fixture.packingId(), versioned(0L, "premature")),
                "PACKING_CHECKLIST_INCOMPLETE");
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.packing.name());
    }

    @Test
    void rejectsTraceablePreparedContentsInsteadOfInventingSerials() {
        Fixture fixture = fixture();
        actor(fixture);
        jdbc.update("UPDATE products SET tracking_serial = true WHERE id = ?", fixture.productId());

        assertCode(
                () -> service.getDetail(fixture.branchId(), fixture.packingId()),
                "TRACEABILITY_NOT_SUPPORTED");
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
        UUID picking = UUID.randomUUID();
        UUID pickingItem = UUID.randomUUID();
        UUID packing = UUID.randomUUID();
        UUID balance = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        String suffix = tenant.toString();
        String orderNumber = "WEB-" + order;
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Packing lifecycle', ?)",
                tenant, "packing-life-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "BR-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Empacador', ?, 'employee', 'active', ?)
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
                     delivery_method, transport_mode, delivery_address, subtotal, discount_total,
                     shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{"name":"Cliente Packing"}'::jsonb, 'packing',
                        'home_delivery', 'third_party', '{"recipientName":"Cliente Packing"}'::jsonb,
                        50, 0, 0, 50, ?)
                """, order, tenant, branch, orderNumber, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                     inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, 'SKU', 'Producto', 5, 5, 10, 0, 50)
                """, orderItem, order, product);
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, order_id, status,
                     priority, completed_at)
                VALUES (?, ?, ?, 'order', ?, ?, 'completed', 'normal', now())
                """, picking, tenant, branch, order, order);
        jdbc.update("""
                INSERT INTO picking_items
                    (id, tenant_id, picking_order_id, source_line_id, order_item_id,
                     product_id, requested_quantity, picked_quantity, status)
                VALUES (?, ?, ?, ?, ?, ?, 5, 5, 'completed')
                """, pickingItem, tenant, picking, orderItem, orderItem, product);
        jdbc.update("""
                INSERT INTO packings
                    (id, tenant_id, branch_id, source_type, source_id, order_id, picking_order_id,
                     started_by_user_id, started_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, now())
                """, packing, tenant, branch, order, order, picking, user);
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
                    (id, tenant_id, branch_id, source_type, source_id, source_line_id,
                     order_id, order_item_id, product_id, quantity, status, allocations)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, ?, 5, 'active', ?::jsonb)
                """, reservation, tenant, branch, order, orderItem, order, orderItem, product, allocations);
        return new Fixture(
                tenant, branch, user, product, order, orderNumber, packing, balance, reservation);
    }

    private void actor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
    }

    private static SavePackingPreparationRequest preparation(
            Long version, String operation, String weight, int packages) {
        return new SavePackingPreparationRequest(
                version,
                operation,
                new PackingChecklistRequest(true, true, true),
                new BigDecimal(weight),
                packages);
    }

    private static PackingVersionedRequest versioned(Long version, String operation) {
        return new PackingVersionedRequest(version, operation);
    }

    private BigDecimal physicalQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, fixture.balanceId());
    }

    private BigDecimal reservedQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, fixture.balanceId());
    }

    private String reservationStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?",
                String.class, fixture.reservationId());
    }

    private String orderStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId());
    }

    private long movementCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?",
                Long.class, fixture.tenantId());
    }

    private long operationCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM packing_operations WHERE tenant_id = ?",
                Long.class, fixture.tenantId());
    }

    private static void assertPackingSnapshot(
            PackingDetailResponse actual, PackingDetailResponse expected) {
        assertThat(actual.packingId()).isEqualTo(expected.packingId());
        assertThat(actual.orderId()).isEqualTo(expected.orderId());
        assertThat(actual.orderReference()).isEqualTo(expected.orderReference());
        assertThat(actual.customerName()).isEqualTo(expected.customerName());
        assertThat(json(actual.storePickupContact())).isEqualTo(json(expected.storePickupContact()));
        assertThat(actual.deliveryMethod()).isEqualTo(expected.deliveryMethod());
        assertThat(actual.sourceType()).isEqualTo(expected.sourceType());
        assertThat(actual.sourceId()).isEqualTo(expected.sourceId());
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.version()).isEqualTo(expected.version());
        assertThat(actual.startedAt()).isEqualTo(expected.startedAt());
        assertThat(actual.updatedAt()).isEqualTo(expected.updatedAt());
        assertThat(actual.pickingOrderId()).isEqualTo(expected.pickingOrderId());
        assertThat(actual.orderStatus()).isEqualTo(expected.orderStatus());
        assertThat(json(actual.deliveryAddress())).isEqualTo(json(expected.deliveryAddress()));
        assertThat(actual.checklist()).isEqualTo(expected.checklist());
        assertThat(actual.totalWeight()).isEqualByComparingTo(expected.totalWeight());
        assertThat(actual.packageCount()).isEqualTo(expected.packageCount());
        assertThat(actual.labelGenerationId()).isEqualTo(expected.labelGenerationId());
        assertThat(actual.labelCode()).isEqualTo(expected.labelCode());
        assertThat(actual.labelGeneratedAt()).isEqualTo(expected.labelGeneratedAt());
        assertThat(actual.labelPrintedAt()).isEqualTo(expected.labelPrintedAt());
        assertThat(actual.finalizedAt()).isEqualTo(expected.finalizedAt());
        assertThat(actual.preparedContents()).isEqualTo(expected.preparedContents());
    }

    private static String json(JsonNode value) {
        return value == null || value.isNull() ? null : value.toString();
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(code);
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID userId,
            UUID productId,
            UUID orderId,
            String orderNumber,
            UUID packingId,
            UUID balanceId,
            UUID reservationId) {}
}
