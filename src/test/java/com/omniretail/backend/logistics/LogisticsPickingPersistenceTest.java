package com.omniretail.backend.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.logistics.entity.PickingAssignmentRelease;
import com.omniretail.backend.logistics.entity.PickingIncident;
import com.omniretail.backend.logistics.entity.PickingIncidentStatus;
import com.omniretail.backend.logistics.entity.PickingIncidentType;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingItemStatus;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingPriority;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PickingAssignmentReleaseRepository;
import com.omniretail.backend.logistics.repository.PickingIncidentRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class LogisticsPickingPersistenceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PickingOrderRepository pickingOrders;
    @Autowired private PickingItemRepository pickingItems;
    @Autowired private PickingIncidentRepository pickingIncidents;
    @Autowired private PickingAssignmentReleaseRepository assignmentReleases;

    private UUID tenantId;
    private UUID branchId;
    private UUID actorId;
    private UUID productId;
    private UUID locationId;
    private UUID orderId;
    private UUID orderItemId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        productId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        orderItemId = UUID.randomUUID();

        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Picking test', ?)",
                tenantId,
                "picking-" + tenantId);
        jdbc.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, 'MAIN', 'Principal', 'main', 'active')
                """,
                branchId,
                tenantId);
        jdbc.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, branch_id)
                VALUES (?, ?, 'Operador', ?, 'employee', ?)
                """,
                actorId,
                tenantId,
                actorId + "@test.local",
                branchId);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Categoria', ?)",
                categoryId,
                tenantId,
                "picking-category-" + categoryId);
        jdbc.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', 'active')
                """,
                unitId,
                tenantId,
                "U-" + unitId.toString().substring(0, 8));
        jdbc.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Producto', ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + productId,
                categoryId,
                unitId);
        jdbc.update(
                """
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')
                """,
                locationId,
                tenantId,
                branchId,
                "LOC-" + locationId.toString().substring(0, 8));
        jdbc.update(
                """
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                     total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{}'::jsonb, 'confirmed', 'home_delivery',
                        'third_party', 10, 0, 0, 10, ?)
                """,
                orderId,
                tenantId,
                branchId,
                "WEB-" + orderId,
                UUID.randomUUID().toString());
        jdbc.update(
                """
                INSERT INTO order_items
                    (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                     inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, 'SKU', 'Producto', 1.250, 1.250, 8, 0, 10)
                """,
                orderItemId,
                orderId,
                productId);
    }

    @Test
    void persistsOrderPickingItemsIncidentsAndAssignmentRelease() {
        PickingOrder picking = persistOrderPicking(PickingStatus.assigned);
        PickingItem item =
                persistOrderItem(picking, new BigDecimal("0.250"), PickingItemStatus.partial);

        PickingIncident incident = PickingIncident.builder()
                .branchId(branchId)
                .pickingOrderId(picking.getId())
                .pickingItemId(item.getId())
                .incidentType(PickingIncidentType.quantity_difference)
                .quantityAffected(new BigDecimal("0.125"))
                .comment("Falta una parte de la cantidad solicitada")
                .createdByUserId(actorId)
                .build();
        incident.setTenantId(tenantId);
        incident = pickingIncidents.saveAndFlush(incident);

        PickingAssignmentRelease release = assignmentReleases.saveAndFlush(
                PickingAssignmentRelease.builder()
                        .tenantId(tenantId)
                        .branchId(branchId)
                        .pickingOrderId(picking.getId())
                        .actorUserId(actorId)
                        .reason("Fin de turno")
                        .build());

        assertThat(pickingOrders.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        tenantId, branchId, PickingSourceType.order, orderId))
                .contains(picking);
        assertThat(pickingOrders.findByTenantIdAndBranchIdAndStatusInOrderByCreatedAtAsc(
                        tenantId, branchId, List.of(PickingStatus.assigned)))
                .extracting(PickingOrder::getId)
                .containsExactly(picking.getId());
        assertThat(pickingOrders.findByTenantIdAndBranchIdAndAssignedUserIdOrderByUpdatedAtDesc(
                        tenantId, branchId, actorId))
                .extracting(PickingOrder::getId)
                .containsExactly(picking.getId());
        assertThat(pickingItems.findByScopeAndPickingOrderId(
                        tenantId, branchId, picking.getId()))
                .extracting(PickingItem::getId)
                .containsExactly(item.getId());
        assertThat(pickingItems.findByScopeAndPickingOrderId(
                        tenantId, UUID.randomUUID(), picking.getId()))
                .isEmpty();
        assertThat(item.getRequestedQuantity()).isEqualByComparingTo("1.250");
        assertThat(item.getPickedQuantity()).isEqualByComparingTo("0.250");
        assertThat(pickingIncidents.existsByTenantIdAndBranchIdAndPickingOrderIdAndStatus(
                        tenantId, branchId, picking.getId(), PickingIncidentStatus.open))
                .isTrue();
        assertThat(incident.getQuantityAffected()).isEqualByComparingTo("0.125");
        assertThat(assignmentReleases
                        .findByTenantIdAndBranchIdAndPickingOrderIdOrderByReleasedAtAsc(
                                tenantId, branchId, picking.getId()))
                .extracting(PickingAssignmentRelease::getId)
                .containsExactly(release.getId());
        assertThat(pickingOrders.findByTenantIdAndBranchIdAndId(
                        UUID.randomUUID(), branchId, picking.getId()))
                .isEmpty();
    }

    @Test
    void supportsFutureTransferIdentityWithoutOrderForeignKeys() {
        UUID transferId = UUID.randomUUID();
        UUID transferLineId = UUID.randomUUID();
        PickingOrder picking = PickingOrder.builder()
                .branchId(branchId)
                .sourceType(PickingSourceType.transfer)
                .sourceId(transferId)
                .build();
        picking.setTenantId(tenantId);
        picking = pickingOrders.saveAndFlush(picking);

        PickingItem item = PickingItem.builder()
                .pickingOrderId(picking.getId())
                .sourceLineId(transferLineId)
                .productId(productId)
                .requestedQuantity(new BigDecimal("2.500"))
                .build();
        item.setTenantId(tenantId);
        item = pickingItems.saveAndFlush(item);

        assertThat(picking.getOrderId()).isNull();
        assertThat(item.getOrderItemId()).isNull();
        assertThat(item.getSourceLineId()).isEqualTo(transferLineId);
    }

    @Test
    void rejectsMoreThanOnePickingForTheSameScopedSource() {
        persistOrderPicking(PickingStatus.pending);

        PickingOrder duplicate = PickingOrder.builder()
                .branchId(branchId)
                .sourceType(PickingSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .build();
        duplicate.setTenantId(tenantId);

        assertThatThrownBy(() -> pickingOrders.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateSourceLineAndProductWithinPicking() {
        PickingOrder picking = persistOrderPicking(PickingStatus.pending);
        persistOrderItem(picking, BigDecimal.ZERO, PickingItemStatus.pending);

        PickingItem duplicate = PickingItem.builder()
                .pickingOrderId(picking.getId())
                .sourceLineId(orderItemId)
                .orderItemId(orderItemId)
                .productId(productId)
                .requestedQuantity(new BigDecimal("1.250"))
                .build();
        duplicate.setTenantId(tenantId);

        assertThatThrownBy(() -> pickingItems.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPickingItemScopedToAnotherTenant() {
        PickingOrder picking = persistOrderPicking(PickingStatus.pending);
        UUID otherTenantId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Other tenant', ?)",
                otherTenantId,
                "other-" + otherTenantId);

        PickingItem foreignItem = PickingItem.builder()
                .pickingOrderId(picking.getId())
                .sourceLineId(orderItemId)
                .orderItemId(orderItemId)
                .productId(productId)
                .requestedQuantity(new BigDecimal("1.250"))
                .build();
        foreignItem.setTenantId(otherTenantId);

        assertThatThrownBy(() -> pickingItems.saveAndFlush(foreignItem))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseContainsAllPickingPersistenceTables() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('picking_orders', 'picking_items',
                                     'picking_incidents', 'picking_assignment_releases')
                """,
                Integer.class);
        Integer changeSetCount = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '032-logistics-picking'",
                Integer.class);

        assertThat(count).isEqualTo(4);
        assertThat(changeSetCount).isEqualTo(1);
    }

    private PickingOrder persistOrderPicking(PickingStatus status) {
        PickingOrder picking = PickingOrder.builder()
                .branchId(branchId)
                .sourceType(PickingSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .assignedUserId(status == PickingStatus.assigned ? actorId : null)
                .status(status)
                .priority(PickingPriority.high)
                .startedAt(status == PickingStatus.assigned ? Instant.now() : null)
                .build();
        picking.setTenantId(tenantId);
        return pickingOrders.saveAndFlush(picking);
    }

    private PickingItem persistOrderItem(
            PickingOrder picking, BigDecimal pickedQuantity, PickingItemStatus status) {
        PickingItem item = PickingItem.builder()
                .pickingOrderId(picking.getId())
                .sourceLineId(orderItemId)
                .orderItemId(orderItemId)
                .productId(productId)
                .requestedQuantity(new BigDecimal("1.250"))
                .pickedQuantity(pickedQuantity)
                .locationId(locationId)
                .status(status)
                .build();
        item.setTenantId(tenantId);
        return pickingItems.saveAndFlush(item);
    }
}
