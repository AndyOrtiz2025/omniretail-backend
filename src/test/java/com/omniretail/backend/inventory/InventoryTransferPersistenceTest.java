package com.omniretail.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import com.omniretail.backend.inventory.entity.InventoryTransferReceipt;
import com.omniretail.backend.inventory.entity.InventoryTransferReceiptItem;
import com.omniretail.backend.inventory.entity.InventoryTransferRequest;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferReceiptItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferReceiptRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
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
class InventoryTransferPersistenceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryTransferRequestRepository requests;
    @Autowired private InventoryTransferRepository transfers;
    @Autowired private InventoryTransferItemRepository items;
    @Autowired private InventoryTransferReceiptRepository receipts;
    @Autowired private InventoryTransferReceiptItemRepository receiptItems;

    private UUID tenantId;
    private UUID sourceBranchId;
    private UUID destinationBranchId;
    private UUID actorId;
    private UUID productId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        sourceBranchId = UUID.randomUUID();
        destinationBranchId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        productId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Transfer test', ?)",
                tenantId,
                "transfer-" + tenantId);
        insertBranch(sourceBranchId, "SOURCE");
        insertBranch(destinationBranchId, "DESTINATION");
        jdbc.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, branch_id)
                VALUES (?, ?, 'Operador', ?, 'employee', ?)
                """,
                actorId,
                tenantId,
                actorId + "@test.local",
                sourceBranchId);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'General', ?)",
                categoryId,
                tenantId,
                "general-" + categoryId);
        jdbc.update(
                """
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """,
                unitId,
                tenantId,
                "U-" + unitId.toString().substring(0, 8));
        jdbc.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Producto traslado', ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + productId.toString().substring(0, 8),
                categoryId,
                unitId);
        jdbc.update(
                """
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, 'RECEIVING', 'Recepción', 'warehouse', 'active')
                """,
                locationId,
                tenantId,
                destinationBranchId);
    }

    @Test
    void persistsTheCompleteTransferAggregateAndUsesTenantScopedRepositories() {
        InventoryTransferRequest request = persistRequest();
        InventoryTransfer transfer = persistTransfer("TR-2026-00001", "create-1");
        InventoryTransferItem item = persistItem(transfer, request.getId());
        InventoryTransferReceipt receipt = persistReceipt(transfer, "confirmation-1");
        InventoryTransferReceiptItem receiptItem = InventoryTransferReceiptItem.builder()
                .receiptId(receipt.getId())
                .transferItemId(item.getId())
                .productId(productId)
                .locationId(locationId)
                .quantity(new BigDecimal("3.125"))
                .build();
        receiptItem.setTenantId(tenantId);
        receiptItem = receiptItems.saveAndFlush(receiptItem);

        assertThat(requests.findByTenantIdAndId(tenantId, request.getId())).contains(request);
        assertThat(requests.findByTenantIdAndId(UUID.randomUUID(), request.getId())).isEmpty();
        assertThat(requests.findForUpdateByTenantIdAndId(tenantId, request.getId()))
                .contains(request);
        assertThat(transfers.findByTenantIdAndNumber(tenantId, transfer.getNumber()))
                .contains(transfer);
        assertThat(transfers.findByTenantIdAndOperationId(tenantId, transfer.getOperationId()))
                .contains(transfer);
        assertThat(transfers.findForUpdateByTenantIdAndId(tenantId, transfer.getId()))
                .contains(transfer);
        assertThat(items.findByTenantIdAndTransferIdOrderByIdAsc(tenantId, transfer.getId()))
                .extracting(InventoryTransferItem::getId)
                .containsExactly(item.getId());
        assertThat(receipts.findByTenantIdAndConfirmationId(tenantId, "confirmation-1"))
                .contains(receipt);
        assertThat(receiptItems.findByTenantIdAndReceiptIdOrderByIdAsc(tenantId, receipt.getId()))
                .extracting(InventoryTransferReceiptItem::getId)
                .containsExactly(receiptItem.getId());
        assertThat(item.getSourceRequestId()).isEqualTo(request.getId());
        assertThat(item.getRequestedQuantity()).isEqualByComparingTo("12.500");
    }

    @Test
    void permitsAnItemWithoutSourceRequest() {
        InventoryTransfer transfer = persistTransfer("TR-2026-00002", "create-2");

        InventoryTransferItem item = persistItem(transfer, null);

        assertThat(item.getSourceRequestId()).isNull();
    }

    @Test
    void permitsSeparateLinesForTheSameProductFromDifferentRequests() {
        InventoryTransferRequest firstRequest = persistRequest();
        InventoryTransferRequest secondRequest = persistRequest();
        InventoryTransfer transfer = persistTransfer("TR-2026-00010", "create-10");

        InventoryTransferItem firstItem = persistItem(transfer, firstRequest.getId());
        InventoryTransferItem secondItem = persistItem(transfer, secondRequest.getId());

        assertThat(items.findByTenantIdAndTransferIdOrderByIdAsc(tenantId, transfer.getId()))
                .extracting(InventoryTransferItem::getId)
                .containsExactlyInAnyOrder(firstItem.getId(), secondItem.getId());
    }

    @Test
    void rejectsReusingTheSameSourceRequest() {
        InventoryTransferRequest request = persistRequest();
        InventoryTransfer firstTransfer = persistTransfer("TR-2026-00011", "create-11");
        InventoryTransfer secondTransfer = persistTransfer("TR-2026-00012", "create-12");
        persistItem(firstTransfer, request.getId());

        assertThatThrownBy(() -> persistItem(secondTransfer, request.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateNumberWithinTenant() {
        persistTransfer("TR-2026-00003", "create-3");

        assertThatThrownBy(() -> persistTransfer("TR-2026-00003", "create-4"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateCreationOperationWithinTenant() {
        persistTransfer("TR-2026-00004", "create-5");

        assertThatThrownBy(() -> persistTransfer("TR-2026-00005", "create-5"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateReceiptConfirmationWithinTenant() {
        InventoryTransfer transfer = persistTransfer("TR-2026-00006", "create-6");
        persistReceipt(transfer, "confirmation-duplicate");

        assertThatThrownBy(() -> persistReceipt(transfer, "confirmation-duplicate"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEqualBranchesForRequest() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_transfer_requests
                            (tenant_id, requesting_branch_id, source_branch_id, product_id,
                             requested_quantity, reason, status, requested_by_user_id, requested_at)
                        VALUES (?, ?, ?, ?, 1.000, 'replenishment', 'requested', ?, now())
                        """,
                        tenantId,
                        sourceBranchId,
                        sourceBranchId,
                        productId,
                        actorId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEqualBranchesForTransfer() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_transfers
                            (tenant_id, number, source_branch_id, destination_branch_id, status,
                             operation_id, operation_fingerprint, prepared_by_user_id, prepared_at)
                        VALUES (?, 'TR-INVALID-BRANCH', ?, ?, 'preparing',
                                'invalid-branch', 'fingerprint', ?, now())
                        """,
                        tenantId,
                        sourceBranchId,
                        sourceBranchId,
                        actorId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonPositiveRequestedQuantity() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_transfer_requests
                            (tenant_id, requesting_branch_id, source_branch_id, product_id,
                             requested_quantity, reason, status, requested_by_user_id, requested_at)
                        VALUES (?, ?, ?, ?, 0.000, 'replenishment', 'requested', ?, now())
                        """,
                        tenantId,
                        destinationBranchId,
                        sourceBranchId,
                        productId,
                        actorId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidQuantityProgression() {
        InventoryTransfer transfer = persistTransfer("TR-2026-00007", "create-7");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_transfer_items
                            (tenant_id, transfer_id, product_id, requested_quantity,
                             dispatched_quantity, received_quantity)
                        VALUES (?, ?, ?, 5.000, 4.000, 4.001)
                        """,
                        tenantId,
                        transfer.getId(),
                        productId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDispatchedQuantityAboveRequestedQuantity() {
        InventoryTransfer transfer = persistTransfer("TR-2026-00008", "create-8");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_transfer_items
                            (tenant_id, transfer_id, product_id, requested_quantity,
                             dispatched_quantity, received_quantity)
                        VALUES (?, ?, ?, 5.000, 5.001, 0.000)
                        """,
                        tenantId,
                        transfer.getId(),
                        productId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonPositiveReceiptItemQuantity() {
        InventoryTransfer transfer = persistTransfer("TR-2026-00009", "create-9");
        InventoryTransferItem item = persistItem(transfer, null);
        InventoryTransferReceipt receipt = persistReceipt(transfer, "confirmation-invalid");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO inventory_transfer_receipt_items
                            (tenant_id, receipt_id, transfer_item_id, product_id,
                             location_id, quantity)
                        VALUES (?, ?, ?, ?, ?, 0.000)
                        """,
                        tenantId,
                        receipt.getId(),
                        item.getId(),
                        productId,
                        locationId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseContainsTransferTablesMigrationConstraintsAndNumericColumns() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('inventory_transfer_requests',
                                             'inventory_transfers',
                                             'inventory_transfer_items',
                                             'inventory_transfer_receipts',
                                             'inventory_transfer_receipt_items')
                        """,
                        Integer.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM databasechangelog WHERE id = '042-inventory-transfers'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN ('uk_inventory_transfers_tenant_number',
                                          'uk_inventory_transfers_tenant_operation',
                                          'uk_transfer_receipts_tenant_confirmation',
                                          'ck_transfer_requests_branches',
                                          'ck_inventory_transfers_branches',
                                          'ck_transfer_items_requested_quantity',
                                          'ck_transfer_items_dispatched_quantity',
                                          'ck_transfer_items_received_quantity',
                                          'ck_transfer_receipt_items_quantity')
                        """,
                        Integer.class))
                .isEqualTo(9);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND data_type = 'numeric'
                          AND numeric_precision = 12
                          AND numeric_scale = 3
                          AND ((table_name = 'inventory_transfer_requests'
                                AND column_name = 'requested_quantity')
                            OR (table_name = 'inventory_transfer_items'
                                AND column_name IN ('requested_quantity',
                                                    'dispatched_quantity', 'received_quantity'))
                            OR (table_name = 'inventory_transfer_receipt_items'
                                AND column_name = 'quantity'))
                        """,
                        Integer.class))
                .isEqualTo(5);
    }

    private void insertBranch(UUID branchId, String code) {
        jdbc.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'warehouse', 'active')
                """,
                branchId,
                tenantId,
                code,
                code);
    }

    private InventoryTransferRequest persistRequest() {
        InventoryTransferRequest request = InventoryTransferRequest.builder()
                .requestingBranchId(destinationBranchId)
                .sourceBranchId(sourceBranchId)
                .productId(productId)
                .requestedQuantity(new BigDecimal("12.500"))
                .reason(InventoryTransferReason.replenishment)
                .requestedByUserId(actorId)
                .requestedAt(Instant.now())
                .build();
        request.setTenantId(tenantId);
        return requests.saveAndFlush(request);
    }

    private InventoryTransfer persistTransfer(String number, String operationId) {
        InventoryTransfer transfer = InventoryTransfer.builder()
                .number(number)
                .sourceBranchId(sourceBranchId)
                .destinationBranchId(destinationBranchId)
                .operationId(operationId)
                .operationFingerprint("fingerprint:" + operationId)
                .reason(InventoryTransferReason.replenishment)
                .preparedByUserId(actorId)
                .preparedAt(Instant.now())
                .build();
        transfer.setTenantId(tenantId);
        return transfers.saveAndFlush(transfer);
    }

    private InventoryTransferItem persistItem(
            InventoryTransfer transfer, UUID sourceRequestId) {
        InventoryTransferItem item = InventoryTransferItem.builder()
                .transferId(transfer.getId())
                .productId(productId)
                .sourceRequestId(sourceRequestId)
                .requestedQuantity(new BigDecimal("12.500"))
                .build();
        item.setTenantId(tenantId);
        return items.saveAndFlush(item);
    }

    private InventoryTransferReceipt persistReceipt(
            InventoryTransfer transfer, String confirmationId) {
        InventoryTransferReceipt receipt = InventoryTransferReceipt.builder()
                .transferId(transfer.getId())
                .confirmationId(confirmationId)
                .operationFingerprint("fingerprint:" + confirmationId)
                .receivedByUserId(actorId)
                .receivedAt(Instant.now())
                .build();
        receipt.setTenantId(tenantId);
        return receipts.saveAndFlush(receipt);
    }
}
