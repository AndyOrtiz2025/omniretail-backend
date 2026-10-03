package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Modifying(clearAutomatically = true)
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

    @Query(
            value = """
                    WITH product_stock AS (
                        SELECT p.id AS product_id,
                               p.sku,
                               p.name AS product_name,
                               p.category_id,
                               c.name AS category_name,
                               p.base_unit_id,
                               COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                               s.reorder_point,
                               s.default_location_id,
                               l.name AS default_location_name,
                               COALESCE(SUM(b.quantity), CAST(0 AS numeric)) AS quantity,
                               COALESCE(SUM(b.reserved_quantity), CAST(0 AS numeric)) AS reserved_quantity,
                               COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                        FROM products p
                        LEFT JOIN categories c
                          ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                        LEFT JOIN product_inventory_settings s
                          ON s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.branch_id = :branchId
                        LEFT JOIN locations l
                          ON l.tenant_id = p.tenant_id AND l.id = s.default_location_id AND l.branch_id = :branchId
                        LEFT JOIN inventory_balances b
                          ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = :branchId
                        WHERE p.tenant_id = :tenantId
                          AND p.status = 'published'
                          AND p.product_type = 'physical'
                          AND p.tracking_stock = TRUE
                          AND (:categoryId IS NULL OR p.category_id = :categoryId)
                          AND (CAST(:search AS text) IS NULL
                               OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(l.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                        GROUP BY p.id, p.sku, p.name, p.category_id, c.name, p.base_unit_id,
                                 s.min_stock, s.reorder_point, s.default_location_id, l.name
                    ), expiration_summary AS (
                        SELECT lot.product_id,
                               MIN(lot.expiration_date) AS next_expiration_date
                        FROM inventory_lots lot
                        JOIN inventory_lot_balances balance
                          ON balance.tenant_id = lot.tenant_id
                         AND balance.lot_id = lot.id
                         AND balance.branch_id = :branchId
                         AND balance.quantity > 0
                        JOIN products expiration_product
                          ON expiration_product.tenant_id = lot.tenant_id
                         AND expiration_product.id = lot.product_id
                        WHERE lot.tenant_id = :tenantId
                          AND lot.expiration_date >= :businessDate
                          AND expiration_product.status = 'published'
                          AND expiration_product.product_type = 'physical'
                          AND expiration_product.tracking_stock = TRUE
                          AND expiration_product.tracking_lot = TRUE
                          AND expiration_product.tracking_expiration = TRUE
                        GROUP BY lot.product_id
                    ), classified AS (
                        SELECT product_stock.*,
                               expiration_summary.next_expiration_date,
                               CASE
                                   WHEN available_quantity <= 0 THEN 'out_of_stock'
                                   WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                                   WHEN min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                                   ELSE 'normal'
                               END AS stock_status,
                               GREATEST(CAST(0 AS numeric), COALESCE(reorder_point, min_stock) - available_quantity)
                                   AS suggested_reorder
                        FROM product_stock
                        LEFT JOIN expiration_summary
                          ON expiration_summary.product_id = product_stock.product_id
                    )
                    SELECT product_id AS "productId", :branchId AS "branchId", sku, product_name AS "productName",
                           category_id AS "categoryId", category_name AS "categoryName", base_unit_id AS "baseUnitId",
                           quantity, reserved_quantity AS "reservedQuantity", available_quantity AS "availableQuantity",
                           min_stock AS "minStock", reorder_point AS "reorderPoint",
                           default_location_id AS "defaultLocationId", default_location_name AS "defaultLocationName",
                           next_expiration_date AS "nextExpirationDate",
                           stock_status AS "stockStatus", suggested_reorder AS "suggestedReorder"
                    FROM classified
                    WHERE stock_status = COALESCE(CAST(:status AS text), stock_status)
                    ORDER BY
                      CASE WHEN :sortField = 'productName' AND :sortDirection = 'asc' THEN LOWER(product_name) END ASC,
                      CASE WHEN :sortField = 'productName' AND :sortDirection = 'desc' THEN LOWER(product_name) END DESC,
                      CASE WHEN :sortField = 'sku' AND :sortDirection = 'asc' THEN LOWER(sku) END ASC,
                      CASE WHEN :sortField = 'sku' AND :sortDirection = 'desc' THEN LOWER(sku) END DESC,
                      CASE WHEN :sortField = 'categoryName' AND :sortDirection = 'asc' THEN LOWER(category_name) END ASC NULLS LAST,
                      CASE WHEN :sortField = 'categoryName' AND :sortDirection = 'desc' THEN LOWER(category_name) END DESC NULLS LAST,
                      CASE WHEN :sortField = 'availableQuantity' AND :sortDirection = 'asc' THEN available_quantity END ASC,
                      CASE WHEN :sortField = 'availableQuantity' AND :sortDirection = 'desc' THEN available_quantity END DESC,
                      CASE WHEN :sortField = 'status' AND :sortDirection = 'asc' THEN stock_status END ASC,
                      CASE WHEN :sortField = 'status' AND :sortDirection = 'desc' THEN stock_status END DESC,
                      product_id ASC
                    """,
            countQuery = """
                    WITH product_stock AS (
                        SELECT p.id AS product_id,
                               COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                               COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                        FROM products p
                        LEFT JOIN categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                        LEFT JOIN product_inventory_settings s
                          ON s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.branch_id = :branchId
                        LEFT JOIN locations l
                          ON l.tenant_id = p.tenant_id AND l.id = s.default_location_id AND l.branch_id = :branchId
                        LEFT JOIN inventory_balances b
                          ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = :branchId
                        WHERE p.tenant_id = :tenantId AND p.status = 'published'
                          AND p.product_type = 'physical' AND p.tracking_stock = TRUE
                          AND (:categoryId IS NULL OR p.category_id = :categoryId)
                          AND (CAST(:search AS text) IS NULL
                               OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(l.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                        GROUP BY p.id, s.min_stock
                    ), classified AS (
                        SELECT CASE WHEN available_quantity <= 0 THEN 'out_of_stock'
                                    WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                                    WHEN min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                                    ELSE 'normal' END AS stock_status
                        FROM product_stock
                    )
                    SELECT COUNT(*) FROM classified
                    WHERE stock_status = COALESCE(CAST(:status AS text), stock_status)
                      AND CAST(:sortField AS text) IS NOT NULL AND CAST(:sortDirection AS text) IS NOT NULL
                      AND CAST(:businessDate AS date) IS NOT NULL
                    """,
            nativeQuery = true)
    Page<InventoryStockProjection> findStock(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("search") String search,
            @Param("categoryId") UUID categoryId,
            @Param("status") String status,
            @Param("businessDate") LocalDate businessDate,
            @Param("sortField") String sortField,
            @Param("sortDirection") String sortDirection,
            Pageable pageable);

    @Query(value = """
            WITH product_stock AS (
                SELECT p.id AS product_id,
                       COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                       COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                FROM products p
                LEFT JOIN categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                LEFT JOIN product_inventory_settings s
                  ON s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.branch_id = :branchId
                LEFT JOIN locations l
                  ON l.tenant_id = p.tenant_id AND l.id = s.default_location_id AND l.branch_id = :branchId
                LEFT JOIN inventory_balances b
                  ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = :branchId
                WHERE p.tenant_id = :tenantId AND p.status = 'published'
                  AND p.product_type = 'physical' AND p.tracking_stock = TRUE
                  AND (:categoryId IS NULL OR p.category_id = :categoryId)
                  AND (CAST(:search AS text) IS NULL
                       OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                       OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                       OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                       OR LOWER(COALESCE(l.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                GROUP BY p.id, s.min_stock
            ), expiration_products AS (
                SELECT DISTINCT lot.product_id
                FROM inventory_lots lot
                JOIN inventory_lot_balances balance
                  ON balance.tenant_id = lot.tenant_id
                 AND balance.lot_id = lot.id
                 AND balance.branch_id = :branchId
                 AND balance.quantity > 0
                JOIN products expiration_product
                  ON expiration_product.tenant_id = lot.tenant_id
                 AND expiration_product.id = lot.product_id
                WHERE lot.tenant_id = :tenantId
                  AND lot.expiration_date BETWEEN CAST(:businessDate AS date)
                                               AND CAST(:businessDate AS date) + 30
                  AND expiration_product.status = 'published'
                  AND expiration_product.product_type = 'physical'
                  AND expiration_product.tracking_stock = TRUE
                  AND expiration_product.tracking_lot = TRUE
                  AND expiration_product.tracking_expiration = TRUE
            ), classified AS (
                SELECT product_id,
                       CASE WHEN available_quantity <= 0 THEN 'out_of_stock'
                            WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                            WHEN min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                            ELSE 'normal' END AS stock_status
                FROM product_stock
            ), filtered AS (
                SELECT product_id, stock_status FROM classified
                WHERE stock_status = COALESCE(CAST(:status AS text), stock_status)
            )
            SELECT COUNT(*) AS "activeProducts",
                   COUNT(*) FILTER (WHERE stock_status IN ('critical', 'near_minimum')) AS "lowStock",
                   COUNT(*) FILTER (WHERE expiration_products.product_id IS NOT NULL)
                       AS "expiringSoonProducts",
                   COUNT(*) FILTER (WHERE stock_status = 'out_of_stock') AS "outOfStock"
            FROM filtered
            LEFT JOIN expiration_products
              ON expiration_products.product_id = filtered.product_id
            """, nativeQuery = true)
    InventoryStockSummaryProjection summarizeStock(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("search") String search,
            @Param("categoryId") UUID categoryId,
            @Param("status") String status,
            @Param("businessDate") LocalDate businessDate);

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

    interface InventoryStockProjection {
        UUID getProductId();
        UUID getBranchId();
        String getSku();
        String getProductName();
        UUID getCategoryId();
        String getCategoryName();
        UUID getBaseUnitId();
        BigDecimal getQuantity();
        BigDecimal getReservedQuantity();
        BigDecimal getAvailableQuantity();
        BigDecimal getMinStock();
        BigDecimal getReorderPoint();
        UUID getDefaultLocationId();
        String getDefaultLocationName();
        LocalDate getNextExpirationDate();
        String getStockStatus();
        BigDecimal getSuggestedReorder();
    }

    interface InventoryStockSummaryProjection {
        long getActiveProducts();
        long getLowStock();
        long getExpiringSoonProducts();
        long getOutOfStock();
    }
}
