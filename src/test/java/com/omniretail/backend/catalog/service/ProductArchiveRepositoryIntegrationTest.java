package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductArchiveRepositoryIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryBalanceRepository inventoryBalanceRepository;
    @Autowired private PurchaseOrderItemRepository purchaseOrderItemRepository;

    private UUID tenantId;
    private UUID productId;
    private UUID branchId;
    private UUID supplierId;
    private UUID supplierProductId;
    private UUID unitId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        productId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        supplierProductId = UUID.randomUUID();
        unitId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Archive query', ?)",
                tenantId,
                "archive-query-" + tenantId);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, 'MAIN', 'Principal', 'main', 'active')
                """, branchId, tenantId);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, branch_id)
                VALUES (?, ?, 'Comprador', ?, 'employee', ?)
                """, actorId, tenantId, actorId + "@test.local", branchId);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Categoria', ?)",
                categoryId,
                tenantId,
                "archive-category-" + categoryId);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', 'active')
                """, unitId, tenantId, "U-" + unitId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Producto', ?, ?)
                """, productId, tenantId, "SKU-" + productId, categoryId, unitId);
        jdbc.update(
                "INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, 'Proveedor', 'active')",
                supplierId,
                tenantId);
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, ?, 1, 10, 0, 1)
                """, supplierProductId, tenantId, supplierId, productId, unitId);
    }

    @Test
    void openStatusesBlockArchiveWhileReceivedAndCancelledDoNot() {
        for (String status : List.of(
                "draft", "pending_approval", "approved", "sent", "partially_received")) {
            UUID orderId = insertOrderWithItem(status);
            assertThat(purchaseOrderItemRepository.existsOpenOrderForProduct(tenantId, productId))
                    .as(status)
                    .isTrue();
            jdbc.update("DELETE FROM purchase_orders WHERE id = ?", orderId);
        }

        insertOrderWithItem("received");
        insertOrderWithItem("cancelled");

        assertThat(purchaseOrderItemRepository.existsOpenOrderForProduct(tenantId, productId))
                .isFalse();
        assertThat(purchaseOrderItemRepository.existsByTenantIdAndProductId(tenantId, productId))
                .isTrue();
        assertThat(purchaseOrderItemRepository.existsOpenOrderForProduct(UUID.randomUUID(), productId))
                .isFalse();
    }

    @Test
    void positiveQuantityOrReservationIsDetectedAcrossProductBalances() {
        jdbc.update("""
                INSERT INTO inventory_balances
                    (tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, NULL, 1, 1)
                """, tenantId, branchId, productId);
        assertThat(inventoryBalanceRepository
                        .existsPositiveStockByTenantIdAndProductId(tenantId, productId))
                .isTrue();

        jdbc.update("""
                UPDATE inventory_balances
                SET quantity = 1, reserved_quantity = 0
                WHERE tenant_id = ? AND product_id = ?
                """, tenantId, productId);
        assertThat(inventoryBalanceRepository
                        .existsPositiveStockByTenantIdAndProductId(tenantId, productId))
                .isTrue();

        jdbc.update("""
                UPDATE inventory_balances
                SET quantity = 0, reserved_quantity = 0
                WHERE tenant_id = ? AND product_id = ?
                """, tenantId, productId);
        assertThat(inventoryBalanceRepository
                        .existsPositiveStockByTenantIdAndProductId(tenantId, productId))
                .isFalse();
    }

    private UUID insertOrderWithItem(String status) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, branch_id, number, supplier_id, supplier_name_snapshot,
                     status, subtotal, total, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'Proveedor', ?, 10, 10, ?)
                """,
                orderId,
                tenantId,
                branchId,
                "PO-" + orderId,
                supplierId,
                status,
                actorId);
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, 'Producto', 'SKU', 1, ?, 'u', 1, 10, 10)
                """,
                tenantId,
                orderId,
                supplierProductId,
                productId,
                unitId);
        return orderId;
    }
}
