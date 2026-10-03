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
import com.omniretail.backend.logistics.dto.CreatePickingIncidentRequest;
import com.omniretail.backend.logistics.dto.PickingLineResponse;
import com.omniretail.backend.logistics.dto.PickingTrackingSelectionRequest;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.entity.PickingIncidentType;
import com.omniretail.backend.logistics.entity.PickingItemStatus;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
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

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PickingServiceTest {

    @Autowired private PickingService service;
    @Autowired private PackingRepository packings;
    @Autowired private PickingOrderRepository pickingOrders;
    @Autowired private PickingItemRepository pickingItems;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowBranch() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void createsExactlyOnePickingForAnEligibleOrder() {
        Fixture fixture = fixture("home_delivery", false);

        PickingOrder first = service.ensureForOrder(fixture.tenantId(), fixture.orderId()).orElseThrow();
        PickingOrder retry = service.ensureForOrder(fixture.tenantId(), fixture.orderId()).orElseThrow();

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(first.getSourceType()).isEqualTo(PickingSourceType.order);
        assertThat(first.getSourceId()).isEqualTo(fixture.orderId());
        assertThat(pickingItems.findByTenantIdAndPickingOrderId(fixture.tenantId(), first.getId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getOrderItemId()).isEqualTo(fixture.orderItemId());
                    assertThat(item.getRequestedQuantity()).isEqualByComparingTo("5.000");
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM picking_orders WHERE tenant_id = ? AND source_id = ?",
                Long.class,
                fixture.tenantId(),
                fixture.orderId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM packings WHERE tenant_id = ? AND source_id = ?",
                Long.class,
                fixture.tenantId(),
                fixture.orderId())).isZero();
    }

    @Test
    void rejectsStorePickupOrders() {
        Fixture storePickup = fixture("store_pickup", false);
        assertCode(
                () -> service.ensureForOrder(storePickup.tenantId(), storePickup.orderId()),
                "PICKING_ORDER_NOT_ELIGIBLE");
    }

    @Test
    void assignmentIsIdempotentAndReleaseIsAppendOnly() {
        Fixture fixture = readyFixture();
        UUID pickingId = pickingId(fixture);

        assertThat(service.assign(fixture.branchId(), pickingId).idempotent()).isFalse();
        assertThat(service.assign(fixture.branchId(), pickingId).idempotent()).isTrue();
        assertThat(orderStatus(fixture)).isEqualTo("preparing");

        service.release(fixture.branchId(), pickingId, "Fin de turno");
        assertThat(service.getDetail(fixture.branchId(), pickingId).releases())
                .singleElement()
                .satisfies(release -> assertThat(release.reason()).isEqualTo("Fin de turno"));
        assertCode(
                () -> service.release(fixture.branchId(), pickingId, "Retry"),
                "PICKING_NOT_ASSIGNED_TO_ACTOR");
        assertThat(releaseCount(fixture)).isOne();
    }

    @Test
    void rejectsAssignmentByAnotherActorAndIsolatesTenantAndBranchReads() {
        Fixture first = readyFixture();
        UUID pickingId = pickingId(first);
        service.assign(first.branchId(), pickingId);

        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(),
                first.tenantId(),
                UserType.employee,
                null,
                first.branchId(),
                UUID.randomUUID()));
        assertCode(
                () -> service.assign(first.branchId(), pickingId),
                "PICKING_ALREADY_ASSIGNED");

        Fixture otherTenant = fixture("home_delivery", false);
        actor(otherTenant);
        assertCode(
                () -> service.getDetail(first.branchId(), pickingId),
                "PICKING_NOT_FOUND");
        assertCode(
                () -> service.getDetail(UUID.randomUUID(), pickingId),
                "PICKING_NOT_FOUND");
    }

    @Test
    void itemUpdatesAreDurablyIdempotentAndReplayTheirOriginalSnapshot() {
        Fixture fixture = readyFixture();
        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);

        PickingLineResponse first = service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("2.000"), null, "operation-a"));
        PickingLineResponse firstRetry = service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("2.000"), null, "operation-a"));
        service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("3.000"), null, "operation-b"));
        service.release(fixture.branchId(), pickingId, "Pausa posterior");
        PickingLineResponse oldRetry = service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("2.000"), null, "operation-a"));

        assertThat(firstRetry).isEqualTo(first);
        assertThat(first.status()).isEqualTo(PickingItemStatus.partial);
        assertThat(first.inventory().physicalQuantity()).isEqualByComparingTo("10.000");
        assertThat(first.inventory().ownReservedQuantity()).isEqualByComparingTo("5.000");
        assertThat(first.inventory().freeQuantity()).isEqualByComparingTo("5.000");
        assertThat(first.inventory().usableQuantity()).isEqualByComparingTo("10.000");
        assertThat(oldRetry.pickedQuantity()).isEqualByComparingTo("2.000");
        assertThat(itemQuantity(itemId)).isEqualByComparingTo("3.000");
        assertThat(operationCount(fixture)).isEqualTo(2);
        assertThat(pickingStatus(pickingId)).isEqualTo("in_progress");
        assertThat(service.getDetail(fixture.branchId(), pickingId).startedAt()).isNotNull();
        assertThat(orderStatus(fixture)).isEqualTo("picking");
    }

    @Test
    void reusingAnOperationIdWithAnotherFingerprintConflicts() {
        Fixture fixture = readyFixture();
        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("2.000"), null, "same-operation"));

        assertCode(
                () -> service.updateItem(
                        fixture.branchId(), pickingId, itemId,
                        new UpdatePickingItemRequest(new BigDecimal("3.000"), null, "same-operation")),
                "PICKING_OPERATION_ID_REUSED");
        assertThat(itemQuantity(itemId)).isEqualByComparingTo("2.000");
    }

    @Test
    void rejectsInvalidQuantitiesAndCrossBranchAccess() {
        Fixture fixture = readyFixture();
        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);

        assertCode(
                () -> service.updateItem(
                        fixture.branchId(), pickingId, itemId,
                        new UpdatePickingItemRequest(new BigDecimal("6.000"), null, "too-many")),
                "PICKING_QUANTITY_EXCEEDED");
        assertThatThrownBy(() -> service.updateItem(
                        fixture.branchId(), pickingId, itemId,
                        new UpdatePickingItemRequest(new BigDecimal("-1.000"), null, "negative")))
                .isInstanceOf(BusinessException.class);
        assertCode(
                () -> service.getDetail(UUID.randomUUID(), pickingId),
                "PICKING_NOT_FOUND");
    }

    @Test
    void incidentsBlockCompletionUntilResolved() {
        Fixture fixture = readyFixture();
        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("5.000"), null, "complete-line"));
        UUID incidentId = service.createIncident(
                        fixture.branchId(),
                        pickingId,
                        new CreatePickingIncidentRequest(
                                itemId,
                                PickingIncidentType.quantity_difference,
                                BigDecimal.ONE,
                                "Verificar cantidad"))
                .id();

        assertCode(
                () -> service.complete(fixture.branchId(), pickingId),
                "PICKING_HAS_OPEN_INCIDENTS");
        service.resolveIncident(fixture.branchId(), pickingId, incidentId);
        assertThat(service.complete(fixture.branchId(), pickingId).idempotent()).isFalse();
        assertThat(service.complete(fixture.branchId(), pickingId).status())
                .isEqualTo(PickingStatus.completed);
        assertThat(service.complete(fixture.branchId(), pickingId).idempotent()).isTrue();
    }

    @Test
    void completionLeavesInventoryAndReservationUntouched() {
        Fixture fixture = readyFixture();
        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        service.updateItem(
                fixture.branchId(), pickingId, itemId,
                new UpdatePickingItemRequest(new BigDecimal("5.000"), null, "finish"));

        service.complete(fixture.branchId(), pickingId);

        assertThat(physicalQuantity(fixture)).isEqualByComparingTo("10.000");
        assertThat(reservedQuantity(fixture)).isEqualByComparingTo("5.000");
        assertThat(reservationStatus(fixture)).isEqualTo(InventoryReservationStatus.active.name());
        assertThat(movementCount(fixture)).isZero();
        assertThat(orderStatus(fixture)).isEqualTo("packing");
        assertThat(packings.findByTenantIdAndBranchIdAndPickingOrderId(
                        fixture.tenantId(), fixture.branchId(), pickingId))
                .isPresent();
        service.complete(fixture.branchId(), pickingId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM packings WHERE tenant_id = ? AND picking_order_id = ?",
                Long.class,
                fixture.tenantId(),
                pickingId))
                .isOne();
    }

    @Test
    void allowsAssigningTraceablePickingBeforePhysicalSelection() {
        Fixture fixture = readyFixture();
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.productId());
        entityManager.flush();
        entityManager.clear();

        assertThat(service.assign(fixture.branchId(), pickingId(fixture)).status())
                .isEqualTo(PickingStatus.assigned);
    }

    @Test
    void traceableLotSelectionCanBeReplacedAndAssignmentReleasePreservesReservation() {
        Fixture fixture = readyFixture();
        UUID location = UUID.randomUUID();
        UUID firstLot = UUID.randomUUID();
        UUID secondLot = UUID.randomUUID();
        jdbc.update("UPDATE products SET tracking_lot = true WHERE id = ?", fixture.productId());
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'TRACE', 'Trazable', 'warehouse', 'active')
                """, location, fixture.tenantId(), fixture.branchId());
        jdbc.update("""
                INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number)
                VALUES (?, ?, ?, 'LOT-A'), (?, ?, ?, 'LOT-B')
                """, firstLot, fixture.tenantId(), fixture.productId(),
                secondLot, fixture.tenantId(), fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 5, 0), (?, ?, ?, ?, ?, 5, 0)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location, firstLot,
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location, secondLot);
        entityManager.flush();
        entityManager.clear();

        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        UpdatePickingItemRequest firstSelection = new UpdatePickingItemRequest(
                new BigDecimal("5.000"),
                location,
                "lot-a",
                List.of(new PickingTrackingSelectionRequest(
                        location, firstLot, new BigDecimal("5.000"), List.of())));
        service.updateItem(
                fixture.branchId(),
                pickingId,
                itemId,
                firstSelection);
        assertThat(lotReserved(firstLot)).isEqualByComparingTo("5.000");
        assertThat(service.updateItem(fixture.branchId(), pickingId, itemId, firstSelection))
                .isNotNull();
        assertCode(
                () -> service.updateItem(
                        fixture.branchId(),
                        pickingId,
                        itemId,
                        new UpdatePickingItemRequest(
                                new BigDecimal("5.0"),
                                location,
                                "lot-a",
                                List.of(new PickingTrackingSelectionRequest(
                                        location, secondLot, new BigDecimal("5"), List.of())))),
                "PICKING_OPERATION_ID_REUSED");
        assertThat(lotReserved(firstLot)).isEqualByComparingTo("5.000");
        assertThat(lotReserved(secondLot)).isEqualByComparingTo("0.000");

        service.updateItem(
                fixture.branchId(),
                pickingId,
                itemId,
                new UpdatePickingItemRequest(
                        new BigDecimal("5.000"),
                        location,
                        "lot-b",
                        List.of(new PickingTrackingSelectionRequest(
                                location, secondLot, new BigDecimal("5.000"), List.of()))));
        assertThat(lotReserved(firstLot)).isEqualByComparingTo("0.000");
        assertThat(lotReserved(secondLot)).isEqualByComparingTo("5.000");

        service.release(fixture.branchId(), pickingId, "Cambio de operador");
        assertThat(lotReserved(secondLot)).isEqualByComparingTo("5.000");
        assertThat(jdbc.queryForObject(
                        "SELECT picked_traces IS NOT NULL FROM picking_items WHERE id = ?",
                        Boolean.class,
                        itemId))
                .isTrue();

        service.assign(fixture.branchId(), pickingId);
        service.complete(fixture.branchId(), pickingId);
        assertThat(lotReserved(secondLot)).isEqualByComparingTo("5.000");
        assertThat(pickingStatus(pickingId)).isEqualTo(PickingStatus.completed.name());
    }

    @Test
    void traceableSerialReplacementReleasesOldAndReservesNewSerial() {
        Fixture fixture = readyFixture();
        UUID location = UUID.randomUUID();
        jdbc.update("UPDATE products SET tracking_serial = true WHERE id = ?", fixture.productId());
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'SERIAL', 'Seriales', 'warehouse', 'active')
                """, location, fixture.tenantId(), fixture.branchId());
        jdbc.update("""
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number, status, version)
                VALUES (?, ?, ?, ?, ?, 'SER-A', 'AVAILABLE', 0),
                       (?, ?, ?, ?, ?, 'SER-B', 'AVAILABLE', 0)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location, fixture.productId(),
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location, fixture.productId());
        entityManager.flush();
        entityManager.clear();

        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        service.updateItem(
                fixture.branchId(),
                pickingId,
                itemId,
                new UpdatePickingItemRequest(
                        BigDecimal.ONE,
                        location,
                        "serial-a",
                        List.of(new PickingTrackingSelectionRequest(
                                location, null, BigDecimal.ONE, List.of("SER-A")))));
        assertThat(serialStatus(fixture, "SER-A")).isEqualTo("RESERVED");

        service.updateItem(
                fixture.branchId(),
                pickingId,
                itemId,
                new UpdatePickingItemRequest(
                        BigDecimal.ONE,
                        location,
                        "serial-b",
                        List.of(new PickingTrackingSelectionRequest(
                                location, null, BigDecimal.ONE, List.of("SER-B")))));
        assertThat(serialStatus(fixture, "SER-A")).isEqualTo("AVAILABLE");
        assertThat(serialStatus(fixture, "SER-B")).isEqualTo("RESERVED");

        service.release(fixture.branchId(), pickingId, "Pausa operativa");
        assertThat(serialStatus(fixture, "SER-B")).isEqualTo("RESERVED");
    }

    @Test
    void traceablePickingRejectsExpiredAndInsufficientLots() {
        Fixture fixture = readyFixture();
        UUID location = UUID.randomUUID();
        UUID expiredLot = UUID.randomUUID();
        UUID insufficientLot = UUID.randomUUID();
        jdbc.update(
                "UPDATE products SET tracking_lot = true, tracking_expiration = true WHERE id = ?",
                fixture.productId());
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'LOT-VALIDATION', 'Validacion lotes', 'warehouse', 'active')
                """, location, fixture.tenantId(), fixture.branchId());
        jdbc.update("""
                INSERT INTO inventory_lots
                    (id, tenant_id, product_id, lot_number, expiration_date)
                VALUES (?, ?, ?, 'EXPIRED', CURRENT_DATE - 1),
                       (?, ?, ?, 'INSUFFICIENT', CURRENT_DATE + 30)
                """, expiredLot, fixture.tenantId(), fixture.productId(),
                insufficientLot, fixture.tenantId(), fixture.productId());
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 5.000, 0.000),
                       (?, ?, ?, ?, ?, 4.000, 0.000)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location, expiredLot,
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location, insufficientLot);
        entityManager.flush();
        entityManager.clear();

        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        assertCode(
                () -> service.updateItem(
                        fixture.branchId(),
                        pickingId,
                        itemId,
                        new UpdatePickingItemRequest(
                                new BigDecimal("5.000"),
                                location,
                                "expired-lot",
                                List.of(new PickingTrackingSelectionRequest(
                                        location,
                                        expiredLot,
                                        new BigDecimal("5.000"),
                                        List.of())))),
                "LOT_EXPIRED");
        assertThat(lotReserved(expiredLot)).isEqualByComparingTo("0.000");

        assertCode(
                () -> service.updateItem(
                        fixture.branchId(),
                        pickingId,
                        itemId,
                        new UpdatePickingItemRequest(
                                new BigDecimal("5.000"),
                                location,
                                "insufficient-lot",
                                List.of(new PickingTrackingSelectionRequest(
                                        location,
                                        insufficientLot,
                                        new BigDecimal("5.000"),
                                        List.of())))),
                "INSUFFICIENT_TRACEABLE_STOCK");
        assertThat(lotReserved(insufficientLot)).isEqualByComparingTo("0.000");
    }

    @Test
    void serialReservedOutsideThePickedTracesCannotBeClaimed() {
        Fixture fixture = readyFixture();
        UUID location = UUID.randomUUID();
        jdbc.update("UPDATE products SET tracking_serial = true WHERE id = ?", fixture.productId());
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'SERIAL-LOCKED', 'Serial reservado', 'warehouse', 'active')
                """, location, fixture.tenantId(), fixture.branchId());
        jdbc.update("""
                INSERT INTO inventory_serials
                    (id, tenant_id, branch_id, location_id, product_id, serial_number, status, version)
                VALUES (?, ?, ?, ?, ?, 'SER-LOCKED', 'RESERVED', 0)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), location,
                fixture.productId());
        entityManager.flush();
        entityManager.clear();

        UUID pickingId = pickingId(fixture);
        UUID itemId = itemId(fixture, pickingId);
        service.assign(fixture.branchId(), pickingId);
        assertCode(
                () -> service.updateItem(
                        fixture.branchId(),
                        pickingId,
                        itemId,
                        new UpdatePickingItemRequest(
                                BigDecimal.ONE,
                                location,
                                "locked-serial",
                                List.of(new PickingTrackingSelectionRequest(
                                        location, null, BigDecimal.ONE, List.of("SER-LOCKED"))))),
                "SERIAL_RESERVATION_CONFLICT");
        assertThat(serialStatus(fixture, "SER-LOCKED")).isEqualTo("RESERVED");
    }

    @Test
    void migration035CreatesTheTenantScopedOperationContract() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'picking_item_update_operations'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM databasechangelog
                WHERE id = '039-logistics-picking-idempotency'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname IN ('uk_picking_items_tenant_id',
                                  'fk_picking_item_operations_tenant_item',
                                  'uk_picking_item_operations_tenant_operation')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE indexname = 'idx_picking_item_operations_tenant_item_created'
                """, Integer.class)).isOne();
    }

    private Fixture readyFixture() {
        Fixture fixture = fixture("home_delivery", true);
        actor(fixture);
        return fixture;
    }

    private Fixture fixture(String deliveryMethod, boolean ensurePicking) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID orderItem = UUID.randomUUID();
        UUID balance = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Picking lifecycle', ?)",
                tenant, "pick-life-" + suffix);
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
                        ?, 'third_party', 50, 0, 0, 50, ?)
                """, order, tenant, branch, "WEB-" + order, deliveryMethod, UUID.randomUUID().toString());
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
                    (id, tenant_id, branch_id, source_type, source_id, source_line_id,
                     order_id, order_item_id, product_id, quantity, status, allocations)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, ?, 5, 'active', ?::jsonb)
                """, reservation, tenant, branch, order, orderItem, order, orderItem, product, allocations);
        Fixture fixture = new Fixture(
                tenant, branch, user, product, order, orderItem, balance, reservation);
        if (ensurePicking) service.ensureForOrder(tenant, order);
        return fixture;
    }

    private void actor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(),
                fixture.tenantId(),
                UserType.employee,
                null,
                fixture.branchId(),
                UUID.randomUUID()));
    }

    private UUID pickingId(Fixture fixture) {
        return pickingOrders.findByTenantIdAndSourceTypeAndSourceId(
                        fixture.tenantId(), PickingSourceType.order, fixture.orderId())
                .orElseThrow()
                .getId();
    }

    private UUID itemId(Fixture fixture, UUID pickingId) {
        return pickingItems.findByTenantIdAndPickingOrderId(fixture.tenantId(), pickingId)
                .getFirst()
                .getId();
    }

    private BigDecimal itemQuantity(UUID itemId) {
        return jdbc.queryForObject(
                "SELECT picked_quantity FROM picking_items WHERE id = ?", BigDecimal.class, itemId);
    }

    private String pickingStatus(UUID pickingId) {
        return jdbc.queryForObject(
                "SELECT status FROM picking_orders WHERE id = ?", String.class, pickingId);
    }

    private String orderStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId());
    }

    private BigDecimal physicalQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class,
                fixture.balanceId());
    }

    private BigDecimal reservedQuantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class,
                fixture.balanceId());
    }

    private BigDecimal lotReserved(UUID lotId) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM inventory_lot_balances WHERE lot_id = ?",
                BigDecimal.class,
                lotId);
    }

    private String serialStatus(Fixture fixture, String serialNumber) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_serials WHERE tenant_id = ? AND serial_number = ?",
                String.class,
                fixture.tenantId(),
                serialNumber);
    }

    private String reservationStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?",
                String.class,
                fixture.reservationId());
    }

    private long movementCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?",
                Long.class,
                fixture.tenantId());
    }

    private long operationCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM picking_item_update_operations WHERE tenant_id = ?",
                Long.class,
                fixture.tenantId());
    }

    private long releaseCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM picking_assignment_releases WHERE tenant_id = ?",
                Long.class,
                fixture.tenantId());
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
            UUID orderItemId,
            UUID balanceId,
            UUID reservationId) {}
}
