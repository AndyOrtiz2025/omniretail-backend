package com.omniretail.backend.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingOperation;
import com.omniretail.backend.logistics.entity.PackingOperationType;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingPriority;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PackingOperationRepository;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
class LogisticsPackingPersistenceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PickingOrderRepository pickingOrders;
    @Autowired private PackingRepository packings;
    @Autowired private PackingOperationRepository operations;

    private UUID tenantId;
    private UUID branchId;
    private UUID actorId;
    private UUID orderId;
    private PickingOrder picking;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Packing test', ?)",
                tenantId,
                "packing-" + tenantId);
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
                VALUES (?, ?, 'Empacador', ?, 'employee', ?)
                """,
                actorId,
                tenantId,
                actorId + "@test.local",
                branchId);
        jdbc.update(
                """
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                     total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{}'::jsonb, 'packing', 'home_delivery',
                        'third_party', 10, 0, 0, 10, ?)
                """,
                orderId,
                tenantId,
                branchId,
                "WEB-" + orderId,
                UUID.randomUUID().toString());

        picking = PickingOrder.builder()
                .branchId(branchId)
                .sourceType(PickingSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .status(PickingStatus.completed)
                .priority(PickingPriority.normal)
                .completedAt(Instant.now())
                .build();
        picking.setTenantId(tenantId);
        picking = pickingOrders.saveAndFlush(picking);
    }

    @Test
    void persistsPackingChecklistLabelVersionAndHistoricalOperation() {
        Instant startedAt = Instant.now();
        Packing packing = persistOrderPacking(startedAt);

        assertThat(packing.getVersion()).isZero();
        assertThat(packing.isPackageProtectionChecked()).isTrue();
        assertThat(packing.isDocumentIncludedChecked()).isTrue();
        assertThat(packing.isRecipientVerifiedChecked()).isTrue();
        assertThat(packing.getTotalWeight()).isEqualByComparingTo("2.750");
        assertThat(packing.getPackageCount()).isEqualTo(2);

        PackingOperation operation = operations.saveAndFlush(PackingOperation.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .packingId(packing.getId())
                .operationId("packing-op-1")
                .operationType(PackingOperationType.save_preparation)
                .fingerprint("{\"type\":\"save_preparation\"}")
                .resultVersion(packing.getVersion())
                .resultPacking("{\"packingId\":\"" + packing.getId() + "\",\"version\":0}")
                .build());

        assertThat(packings.findByTenantIdAndBranchIdAndId(tenantId, branchId, packing.getId()))
                .contains(packing);
        assertThat(packings.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        tenantId, branchId, PackingSourceType.order, orderId))
                .contains(packing);
        assertThat(packings.findByTenantIdAndBranchIdAndPickingOrderId(
                        tenantId, branchId, picking.getId()))
                .contains(packing);
        assertThat(packings.findByTenantIdAndBranchIdAndStatusOrderByCreatedAtAsc(
                        tenantId, branchId, PackingStatus.in_progress))
                .extracting(Packing::getId)
                .containsExactly(packing.getId());
        assertThat(operations.findByTenantIdAndOperationId(tenantId, "packing-op-1"))
                .contains(operation);
        assertThat(operation.getResultPacking()).contains(packing.getId().toString());
    }

    @Test
    void incrementsVersionOptimisticallyWhenPackingChanges() {
        Packing packing = persistOrderPacking(Instant.now());

        packing.setPackageCount(3);
        packings.saveAndFlush(packing);

        assertThat(packing.getVersion()).isEqualTo(1L);
    }

    @Test
    void supportsFutureTransferIdentityWithoutOrderForeignKey() {
        UUID transferId = UUID.randomUUID();
        PickingOrder transferPicking = PickingOrder.builder()
                .branchId(branchId)
                .sourceType(PickingSourceType.transfer)
                .sourceId(transferId)
                .status(PickingStatus.completed)
                .completedAt(Instant.now())
                .build();
        transferPicking.setTenantId(tenantId);
        transferPicking = pickingOrders.saveAndFlush(transferPicking);

        Packing packing = Packing.builder()
                .branchId(branchId)
                .sourceType(PackingSourceType.transfer)
                .sourceId(transferId)
                .pickingOrderId(transferPicking.getId())
                .startedByUserId(actorId)
                .startedAt(Instant.now())
                .build();
        packing.setTenantId(tenantId);
        packing = packings.saveAndFlush(packing);

        assertThat(packing.getOrderId()).isNull();
        assertThat(packings.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                        tenantId, branchId, PackingSourceType.transfer, transferId))
                .contains(packing);
    }

    @Test
    void rejectsMoreThanOnePackingForSameSourceOrPicking() {
        persistOrderPacking(Instant.now());

        Packing duplicate = Packing.builder()
                .branchId(branchId)
                .sourceType(PackingSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .pickingOrderId(picking.getId())
                .startedByUserId(actorId)
                .startedAt(Instant.now())
                .build();
        duplicate.setTenantId(tenantId);

        assertThatThrownBy(() -> packings.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPackingScopedToDifferentTenantThanPicking() {
        UUID otherTenantId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Other tenant', ?)",
                otherTenantId,
                "other-packing-" + otherTenantId);

        Packing foreign = Packing.builder()
                .branchId(branchId)
                .sourceType(PackingSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .pickingOrderId(picking.getId())
                .startedByUserId(actorId)
                .startedAt(Instant.now())
                .build();
        foreign.setTenantId(otherTenantId);

        assertThatThrownBy(() -> packings.saveAndFlush(foreign))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPackingWhoseSourceDoesNotMatchPicking() {
        Packing mismatched = Packing.builder()
                .branchId(branchId)
                .sourceType(PackingSourceType.transfer)
                .sourceId(UUID.randomUUID())
                .pickingOrderId(picking.getId())
                .startedByUserId(actorId)
                .startedAt(Instant.now())
                .build();
        mismatched.setTenantId(tenantId);

        assertThatThrownBy(() -> packings.saveAndFlush(mismatched))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReusedOperationIdWithinTenant() {
        Packing packing = persistOrderPacking(Instant.now());
        persistOperation(packing, "same-operation", PackingOperationType.generate_label);

        PackingOperation duplicate = PackingOperation.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .packingId(packing.getId())
                .operationId("same-operation")
                .operationType(PackingOperationType.finalize)
                .fingerprint("different")
                .resultVersion(0L)
                .resultPacking("{\"version\":0}")
                .build();

        assertThatThrownBy(() -> operations.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseContainsPackingPersistenceAndConstraints() {
        Integer tableCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('packings', 'packing_operations')
                """,
                Integer.class);
        Integer changeSetCount = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '040-logistics-packing'",
                Integer.class);
        Integer constraintCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_constraint
                WHERE conname IN ('uk_packings_tenant_source', 'uk_packings_tenant_picking',
                                  'uk_picking_orders_packing_source',
                                  'fk_packings_tenant_branch_picking_source',
                                  'uk_packing_operations_tenant_operation',
                                  'fk_packing_operations_tenant_branch_packing')
                """,
                Integer.class);
        Integer indexCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE indexname IN ('idx_packings_tenant_branch_status_created',
                                    'idx_packing_operations_tenant_branch_packing_created')
                """,
                Integer.class);

        assertThat(tableCount).isEqualTo(2);
        assertThat(changeSetCount).isEqualTo(1);
        assertThat(constraintCount).isEqualTo(6);
        assertThat(indexCount).isEqualTo(2);
    }

    private Packing persistOrderPacking(Instant startedAt) {
        Packing packing = Packing.builder()
                .branchId(branchId)
                .sourceType(PackingSourceType.order)
                .sourceId(orderId)
                .orderId(orderId)
                .pickingOrderId(picking.getId())
                .packageProtectionChecked(true)
                .documentIncludedChecked(true)
                .recipientVerifiedChecked(true)
                .totalWeight(new BigDecimal("2.750"))
                .packageCount(2)
                .labelGenerationId("packing-label-generation-1")
                .labelCode("LBL-WEB-001-1")
                .labelGeneratedAt(startedAt)
                .startedByUserId(actorId)
                .startedAt(startedAt)
                .build();
        packing.setTenantId(tenantId);
        return packings.saveAndFlush(packing);
    }

    private PackingOperation persistOperation(
            Packing packing, String operationId, PackingOperationType type) {
        return operations.saveAndFlush(PackingOperation.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .packingId(packing.getId())
                .operationId(operationId)
                .operationType(type)
                .fingerprint(type.name())
                .resultVersion(packing.getVersion())
                .resultPacking("{\"version\":" + packing.getVersion() + "}")
                .build());
    }
}
