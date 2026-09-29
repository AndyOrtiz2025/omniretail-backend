package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductInventorySettingsRepository
        extends JpaRepository<ProductInventorySettings, UUID> {

    Optional<ProductInventorySettings> findByTenantIdAndBranchIdAndProductId(
            UUID tenantId, UUID branchId, UUID productId);

    @Query("""
            select settings from ProductInventorySettings settings
            where settings.tenantId = :tenantId
              and settings.branchId = :branchId
              and (:productId is null or settings.productId = :productId)
            """)
    Page<ProductInventorySettings> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            Pageable pageable);

    @Modifying
    @Query(value = """
            INSERT INTO product_inventory_settings
                (tenant_id, branch_id, product_id, min_stock, reorder_point, default_location_id)
            VALUES (:tenantId, :branchId, :productId, :minStock, :reorderPoint, :defaultLocationId)
            ON CONFLICT (tenant_id, branch_id, product_id)
            DO UPDATE SET
                min_stock = EXCLUDED.min_stock,
                reorder_point = EXCLUDED.reorder_point,
                default_location_id = EXCLUDED.default_location_id,
                updated_at = now()
            """, nativeQuery = true)
    void upsert(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("minStock") BigDecimal minStock,
            @Param("reorderPoint") BigDecimal reorderPoint,
            @Param("defaultLocationId") UUID defaultLocationId);

    @Query(
            value = """
                    WITH product_stock AS (
                        SELECT
                            p.id AS product_id,
                            p.sku AS sku,
                            p.name AS product_name,
                            p.base_unit_id AS base_unit_id,
                            COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                            s.reorder_point AS reorder_point,
                            s.default_location_id AS default_location_id,
                            COALESCE(SUM(b.quantity), CAST(0 AS numeric)) AS quantity,
                            COALESCE(SUM(b.reserved_quantity), CAST(0 AS numeric)) AS reserved_quantity,
                            COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                        FROM products p
                        LEFT JOIN product_inventory_settings s
                          ON s.tenant_id = p.tenant_id
                         AND s.product_id = p.id
                         AND s.branch_id = :branchId
                        LEFT JOIN inventory_balances b
                          ON b.tenant_id = p.tenant_id
                         AND b.product_id = p.id
                         AND b.branch_id = :branchId
                        WHERE p.tenant_id = :tenantId
                          AND p.status = 'published'
                          AND p.product_type = 'physical'
                          AND p.tracking_stock = TRUE
                        GROUP BY p.id, p.sku, p.name, p.base_unit_id,
                                 s.min_stock, s.reorder_point, s.default_location_id
                    ), classified AS (
                        SELECT product_stock.*,
                               CASE
                                   WHEN available_quantity <= 0 THEN 'out_of_stock'
                                   WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                                   WHEN min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                                   ELSE 'normal'
                               END AS alert_status,
                               GREATEST(
                                   CAST(0 AS numeric),
                                   COALESCE(reorder_point, min_stock) - available_quantity
                               ) AS suggested_reorder
                        FROM product_stock
                    )
                    SELECT
                        product_id AS "productId",
                        :branchId AS "branchId",
                        sku AS sku,
                        product_name AS "productName",
                        base_unit_id AS "baseUnitId",
                        quantity AS quantity,
                        reserved_quantity AS "reservedQuantity",
                        available_quantity AS "availableQuantity",
                        min_stock AS "minStock",
                        reorder_point AS "reorderPoint",
                        default_location_id AS "defaultLocationId",
                        alert_status AS "alertStatus",
                        suggested_reorder AS "suggestedReorder"
                    FROM classified
                    WHERE alert_status <> 'normal'
                      AND alert_status = COALESCE(CAST(:status AS text), alert_status)
                    ORDER BY
                        CASE alert_status
                            WHEN 'out_of_stock' THEN 1
                            WHEN 'critical' THEN 2
                            WHEN 'near_minimum' THEN 3
                            ELSE 4
                        END,
                        product_name ASC,
                        product_id ASC
                    """,
            countQuery = """
                    WITH product_stock AS (
                        SELECT
                            p.id AS product_id,
                            COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                            COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                        FROM products p
                        LEFT JOIN product_inventory_settings s
                          ON s.tenant_id = p.tenant_id
                         AND s.product_id = p.id
                         AND s.branch_id = :branchId
                        LEFT JOIN inventory_balances b
                          ON b.tenant_id = p.tenant_id
                         AND b.product_id = p.id
                         AND b.branch_id = :branchId
                        WHERE p.tenant_id = :tenantId
                          AND p.status = 'published'
                          AND p.product_type = 'physical'
                          AND p.tracking_stock = TRUE
                        GROUP BY p.id, s.min_stock
                    ), classified AS (
                        SELECT CASE
                                   WHEN available_quantity <= 0 THEN 'out_of_stock'
                                   WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                                   WHEN min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                                   ELSE 'normal'
                               END AS alert_status
                        FROM product_stock
                    )
                    SELECT COUNT(*)
                    FROM classified
                    WHERE alert_status <> 'normal'
                      AND alert_status = COALESCE(CAST(:status AS text), alert_status)
                    """,
            nativeQuery = true)
    Page<InventoryAlertProjection> findAlerts(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("status") String status,
            Pageable pageable);

    interface InventoryAlertProjection {
        UUID getProductId();

        UUID getBranchId();

        String getSku();

        String getProductName();

        UUID getBaseUnitId();

        BigDecimal getQuantity();

        BigDecimal getReservedQuantity();

        BigDecimal getAvailableQuantity();

        BigDecimal getMinStock();

        BigDecimal getReorderPoint();

        UUID getDefaultLocationId();

        String getAlertStatus();

        BigDecimal getSuggestedReorder();
    }
}
