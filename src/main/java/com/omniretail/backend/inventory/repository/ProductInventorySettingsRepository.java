package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
                                   WHEN reorder_point IS NOT NULL AND available_quantity <= reorder_point THEN 'near_minimum'
                                   WHEN reorder_point IS NULL AND min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
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
                            s.reorder_point AS reorder_point,
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
                        GROUP BY p.id, s.min_stock, s.reorder_point
                    ), classified AS (
                        SELECT CASE
                                   WHEN available_quantity <= 0 THEN 'out_of_stock'
                                   WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                                   WHEN reorder_point IS NOT NULL AND available_quantity <= reorder_point THEN 'near_minimum'
                                   WHEN reorder_point IS NULL AND min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
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

    /**
     * Listado de stock del tenant/sucursal sobre un conjunto mezclado de filas:
     * physical (stock real, alertas), service (informativo, sin stock) y kit (disponibilidad DERIVADA de sus
     * componentes físicos). Solo las filas physical tienen balances y estado físico; service/kit nunca generan
     * balances ni alertas. Disponibilidad del kit = MIN(FLOOR(GREATEST(disponible_componente, 0) / quantity_per_kit));
     * un kit sin componentes, o con algún componente que ya no es physical + published + tracking_stock, vale 0.
     */
    @Query(
            value = """
                    WITH product_stock AS (
                        SELECT p.id AS product_id,
                               p.sku,
                               p.name AS product_name,
                               p.category_id,
                               c.name AS category_name,
                               p.base_unit_id,
                               COALESCE(p.inventory_unit_id, p.base_unit_id) AS inventory_unit_id,
                               COALESCE(p.sale_unit_id, p.base_unit_id) AS sale_unit_id,
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
                          AND :includePhysical = TRUE
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
                                 p.inventory_unit_id, p.sale_unit_id,
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
                                   WHEN reorder_point IS NOT NULL AND available_quantity <= reorder_point THEN 'near_minimum'
                                   WHEN reorder_point IS NULL AND min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                                   ELSE 'normal'
                               END AS stock_status,
                               GREATEST(CAST(0 AS numeric), COALESCE(reorder_point, min_stock) - available_quantity)
                                   AS suggested_reorder
                        FROM product_stock
                        LEFT JOIN expiration_summary
                          ON expiration_summary.product_id = product_stock.product_id
                    ), service_rows AS (
                        SELECT p.id AS product_id, p.sku, p.name AS product_name, p.category_id,
                               c.name AS category_name, p.base_unit_id
                        FROM products p
                        LEFT JOIN categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                        WHERE p.tenant_id = :tenantId
                          AND :includeService = TRUE
                          AND CAST(:status AS text) IS NULL
                          AND :lowStock = FALSE
                          AND p.status = 'published'
                          AND p.product_type = 'service'
                          AND (:categoryId IS NULL OR p.category_id = :categoryId)
                          AND (CAST(:search AS text) IS NULL
                               OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                    ), kit_component_availability AS (
                        SELECT kc.id AS kit_component_id,
                               kc.kit_product_id,
                               CASE
                                   WHEN cp.id IS NOT NULL
                                    AND cp.product_type = 'physical'
                                    AND cp.status = 'published'
                                    AND cp.tracking_stock = TRUE
                                   THEN FLOOR(GREATEST(COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)),
                                                       CAST(0 AS numeric)) / kc.quantity_per_kit)
                                   ELSE CAST(0 AS numeric)
                               END AS kits_available
                        FROM product_kit_components kc
                        LEFT JOIN products cp
                          ON cp.tenant_id = kc.tenant_id AND cp.id = kc.component_product_id
                        LEFT JOIN inventory_balances b
                          ON b.tenant_id = kc.tenant_id
                         AND b.product_id = kc.component_product_id
                         AND b.branch_id = :branchId
                        WHERE kc.tenant_id = :tenantId
                          AND :includeKit = TRUE
                        GROUP BY kc.id, kc.kit_product_id, kc.quantity_per_kit,
                                 cp.id, cp.product_type, cp.status, cp.tracking_stock
                    ), kit_availability AS (
                        SELECT kit_product_id, MIN(kits_available) AS available_quantity
                        FROM kit_component_availability
                        GROUP BY kit_product_id
                    ), kit_rows AS (
                        SELECT p.id AS product_id, p.sku, p.name AS product_name, p.category_id,
                               c.name AS category_name, p.base_unit_id,
                               COALESCE(ka.available_quantity, CAST(0 AS numeric)) AS available_quantity
                        FROM products p
                        LEFT JOIN categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                        LEFT JOIN kit_availability ka ON ka.kit_product_id = p.id
                        WHERE p.tenant_id = :tenantId
                          AND :includeKit = TRUE
                          AND CAST(:status AS text) IS NULL
                          AND :lowStock = FALSE
                          AND p.status = 'published'
                          AND p.product_type = 'kit'
                          AND (:categoryId IS NULL OR p.category_id = :categoryId)
                          AND (CAST(:search AS text) IS NULL
                               OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                    ), all_rows AS (
                        SELECT product_id, sku, product_name, category_id, category_name, base_unit_id,
                               'physical' AS product_type, 'TRACKED' AS inventory_mode,
                               quantity, reserved_quantity, available_quantity, min_stock, reorder_point,
                               default_location_id, default_location_name, next_expiration_date,
                               stock_status, suggested_reorder,
                               UPPER(stock_status) AS display_status,
                               stock_status AS status_sort_key,
                               inventory_unit_id,
                               sale_unit_id
                        FROM classified
                        WHERE stock_status = COALESCE(CAST(:status AS text), stock_status)
                          AND (:lowStock = FALSE OR stock_status IN ('critical', 'near_minimum'))
                        UNION ALL
                        SELECT product_id, sku, product_name, category_id, category_name, base_unit_id,
                               'service', 'NONE',
                               CAST(NULL AS numeric), CAST(NULL AS numeric), CAST(NULL AS numeric),
                               CAST(NULL AS numeric), CAST(NULL AS numeric),
                               CAST(NULL AS uuid), CAST(NULL AS text), CAST(NULL AS date),
                               CAST(NULL AS text), CAST(NULL AS numeric),
                               'NOT_CONTROLLED',
                               'zzz_not_controlled',
                               CAST(NULL AS uuid), CAST(NULL AS uuid)
                        FROM service_rows
                        UNION ALL
                        SELECT product_id, sku, product_name, category_id, category_name, base_unit_id,
                               'kit', 'DERIVED_KIT',
                               CAST(NULL AS numeric), CAST(NULL AS numeric), available_quantity,
                               CAST(NULL AS numeric), CAST(NULL AS numeric),
                               CAST(NULL AS uuid), CAST(NULL AS text), CAST(NULL AS date),
                               CAST(NULL AS text), CAST(NULL AS numeric),
                               CASE WHEN available_quantity > 0 THEN 'KIT_AVAILABLE' ELSE 'KIT_UNAVAILABLE' END,
                               CASE WHEN available_quantity > 0 THEN 'zz_kit_available' ELSE 'zz_kit_unavailable' END,
                               CAST(NULL AS uuid), CAST(NULL AS uuid)
                        FROM kit_rows
                    )
                    SELECT product_id AS "productId", :branchId AS "branchId", sku, product_name AS "productName",
                           category_id AS "categoryId", category_name AS "categoryName", base_unit_id AS "baseUnitId",
                           product_type AS "productType", inventory_mode AS "inventoryMode",
                           quantity, reserved_quantity AS "reservedQuantity", available_quantity AS "availableQuantity",
                           min_stock AS "minStock", reorder_point AS "reorderPoint",
                           default_location_id AS "defaultLocationId", default_location_name AS "defaultLocationName",
                           next_expiration_date AS "nextExpirationDate",
                           stock_status AS "stockStatus", suggested_reorder AS "suggestedReorder",
                           display_status AS "displayStatus",
                           inventory_unit_id AS "inventoryUnitId",
                           sale_unit_id AS "saleUnitId",
                           CASE
                               WHEN inventory_unit_id IS NULL THEN CAST(NULL AS numeric)
                               WHEN inventory_unit_id = base_unit_id THEN CAST(1 AS numeric)
                               ELSE COALESCE(
                                   (SELECT uc.factor FROM unit_conversions uc
                                     WHERE uc.tenant_id = :tenantId AND uc.product_id = all_rows.product_id
                                       AND uc.from_unit_id = all_rows.inventory_unit_id
                                       AND uc.to_unit_id = all_rows.base_unit_id),
                                   (SELECT uc.factor FROM unit_conversions uc
                                     WHERE uc.tenant_id = :tenantId AND uc.product_id IS NULL
                                       AND uc.from_unit_id = all_rows.inventory_unit_id
                                       AND uc.to_unit_id = all_rows.base_unit_id))
                           END AS "inventoryToBaseFactor",
                           CASE
                               WHEN sale_unit_id IS NULL THEN CAST(NULL AS numeric)
                               WHEN sale_unit_id = base_unit_id THEN CAST(1 AS numeric)
                               ELSE COALESCE(
                                   (SELECT uc.factor FROM unit_conversions uc
                                     WHERE uc.tenant_id = :tenantId AND uc.product_id = all_rows.product_id
                                       AND uc.from_unit_id = all_rows.sale_unit_id
                                       AND uc.to_unit_id = all_rows.base_unit_id),
                                   (SELECT uc.factor FROM unit_conversions uc
                                     WHERE uc.tenant_id = :tenantId AND uc.product_id IS NULL
                                       AND uc.from_unit_id = all_rows.sale_unit_id
                                       AND uc.to_unit_id = all_rows.base_unit_id))
                           END AS "saleToBaseFactor"
                    FROM all_rows
                    ORDER BY
                      CASE WHEN :sortField = 'productName' AND :sortDirection = 'asc' THEN LOWER(product_name) END ASC,
                      CASE WHEN :sortField = 'productName' AND :sortDirection = 'desc' THEN LOWER(product_name) END DESC,
                      CASE WHEN :sortField = 'sku' AND :sortDirection = 'asc' THEN LOWER(sku) END ASC,
                      CASE WHEN :sortField = 'sku' AND :sortDirection = 'desc' THEN LOWER(sku) END DESC,
                      CASE WHEN :sortField = 'categoryName' AND :sortDirection = 'asc' THEN LOWER(category_name) END ASC NULLS LAST,
                      CASE WHEN :sortField = 'categoryName' AND :sortDirection = 'desc' THEN LOWER(category_name) END DESC NULLS LAST,
                      CASE WHEN :sortField = 'availableQuantity' AND :sortDirection = 'asc' THEN available_quantity END ASC NULLS LAST,
                      CASE WHEN :sortField = 'availableQuantity' AND :sortDirection = 'desc' THEN available_quantity END DESC NULLS LAST,
                      CASE WHEN :sortField = 'status' AND :sortDirection = 'asc' THEN status_sort_key END ASC,
                      CASE WHEN :sortField = 'status' AND :sortDirection = 'desc' THEN status_sort_key END DESC,
                      product_id ASC
                    """,
            countQuery = """
                    WITH product_stock AS (
                        SELECT p.id AS product_id,
                               COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                               s.reorder_point AS reorder_point,
                               COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                        FROM products p
                        LEFT JOIN categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                        LEFT JOIN product_inventory_settings s
                          ON s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.branch_id = :branchId
                        LEFT JOIN locations l
                          ON l.tenant_id = p.tenant_id AND l.id = s.default_location_id AND l.branch_id = :branchId
                        LEFT JOIN inventory_balances b
                          ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = :branchId
                        WHERE p.tenant_id = :tenantId AND :includePhysical = TRUE AND p.status = 'published'
                          AND p.product_type = 'physical' AND p.tracking_stock = TRUE
                          AND (:categoryId IS NULL OR p.category_id = :categoryId)
                          AND (CAST(:search AS text) IS NULL
                               OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(l.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                        GROUP BY p.id, s.min_stock, s.reorder_point
                    ), classified AS (
                        SELECT CASE WHEN available_quantity <= 0 THEN 'out_of_stock'
                                    WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                                    WHEN reorder_point IS NOT NULL AND available_quantity <= reorder_point THEN 'near_minimum'
                                    WHEN reorder_point IS NULL AND min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                                    ELSE 'normal' END AS stock_status
                        FROM product_stock
                    ), non_physical AS (
                        SELECT p.id AS product_id
                        FROM products p
                        LEFT JOIN categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                        WHERE p.tenant_id = :tenantId
                          AND CAST(:status AS text) IS NULL
                          AND :lowStock = FALSE
                          AND p.status = 'published'
                          AND ((:includeService = TRUE AND p.product_type = 'service')
                               OR (:includeKit = TRUE AND p.product_type = 'kit'))
                          AND (:categoryId IS NULL OR p.category_id = :categoryId)
                          AND (CAST(:search AS text) IS NULL
                               OR LOWER(p.name) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%')
                               OR LOWER(COALESCE(c.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS text)), '%'))
                    )
                    SELECT (SELECT COUNT(*) FROM classified
                             WHERE stock_status = COALESCE(CAST(:status AS text), stock_status)
                               AND (:lowStock = FALSE OR stock_status IN ('critical', 'near_minimum')))
                           + (SELECT COUNT(*) FROM non_physical)
                    WHERE CAST(:sortField AS text) IS NOT NULL AND CAST(:sortDirection AS text) IS NOT NULL
                      AND CAST(:businessDate AS date) IS NOT NULL
                    """,
            nativeQuery = true)
    Page<InventoryStockProjection> findStock(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("search") String search,
            @Param("categoryId") UUID categoryId,
            @Param("status") String status,
            @Param("lowStock") boolean lowStock,
            @Param("includePhysical") boolean includePhysical,
            @Param("includeService") boolean includeService,
            @Param("includeKit") boolean includeKit,
            @Param("businessDate") LocalDate businessDate,
            @Param("sortField") String sortField,
            @Param("sortDirection") String sortDirection,
            Pageable pageable);

    @Query(value = """
            WITH product_stock AS (
                SELECT p.id AS product_id,
                       COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                       s.reorder_point AS reorder_point,
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
                GROUP BY p.id, s.min_stock, s.reorder_point
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
                            WHEN reorder_point IS NOT NULL AND available_quantity <= reorder_point THEN 'near_minimum'
                            WHEN reorder_point IS NULL AND min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                            ELSE 'normal' END AS stock_status
                FROM product_stock
            ), filtered AS (
                SELECT product_id, stock_status FROM classified
                WHERE stock_status = COALESCE(CAST(:status AS text), stock_status)
                  AND (:lowStock = FALSE OR stock_status IN ('critical', 'near_minimum'))
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
            @Param("lowStock") boolean lowStock,
            @Param("businessDate") LocalDate businessDate);

    /**
     * Stock físico de varios productos de una sucursal en UNA query (sin filtros de estado de producto). Misma
     * clasificación y reorden sugerido que {@code findStock}; solo productos físicos con control de stock.
     */
    @Query(value = """
            WITH product_stock AS (
                SELECT p.id AS product_id,
                       COALESCE(s.min_stock, CAST(0 AS numeric)) AS min_stock,
                       s.reorder_point AS reorder_point,
                       COALESCE(SUM(b.quantity), CAST(0 AS numeric)) AS quantity,
                       COALESCE(SUM(b.reserved_quantity), CAST(0 AS numeric)) AS reserved_quantity,
                       COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)) AS available_quantity
                FROM products p
                LEFT JOIN product_inventory_settings s
                  ON s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.branch_id = :branchId
                LEFT JOIN inventory_balances b
                  ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = :branchId
                WHERE p.tenant_id = :tenantId
                  AND p.id IN (:productIds)
                  AND p.product_type = 'physical'
                  AND p.tracking_stock = TRUE
                GROUP BY p.id, s.min_stock, s.reorder_point
            )
            SELECT product_id AS "productId", quantity AS quantity, reserved_quantity AS "reservedQuantity",
                   available_quantity AS "availableQuantity", min_stock AS "minStock",
                   reorder_point AS "reorderPoint",
                   CASE
                       WHEN available_quantity <= 0 THEN 'out_of_stock'
                       WHEN min_stock > 0 AND available_quantity < min_stock THEN 'critical'
                       WHEN reorder_point IS NOT NULL AND available_quantity <= reorder_point THEN 'near_minimum'
                       WHEN reorder_point IS NULL AND min_stock > 0 AND available_quantity <= min_stock * 1.25 THEN 'near_minimum'
                       ELSE 'normal'
                   END AS "stockStatus",
                   GREATEST(CAST(0 AS numeric), COALESCE(reorder_point, min_stock) - available_quantity)
                       AS "suggestedReorder"
            FROM product_stock
            ORDER BY product_id
            """, nativeQuery = true)
    List<InventoryStockBatchProjection> findStockBatch(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productIds") java.util.Collection<UUID> productIds);

    interface InventoryStockBatchProjection {
        UUID getProductId();
        BigDecimal getQuantity();
        BigDecimal getReservedQuantity();
        BigDecimal getAvailableQuantity();
        BigDecimal getMinStock();
        BigDecimal getReorderPoint();
        String getStockStatus();
        BigDecimal getSuggestedReorder();
    }

    /**
     * Componentes de UN kit con su disponibilidad efectiva en la sucursal (misma semántica que el listado de
     * stock: disponible = SUM(quantity - reserved); capacidad = FLOOR(GREATEST(disponible, 0) / quantity_per_kit);
     * un componente que ya no es physical + published + tracking_stock vale 0). Una sola query, orden determinístico.
     */
    @Query(value = """
            WITH component_availability AS (
                SELECT kc.component_product_id AS component_product_id,
                       cp.sku AS sku,
                       cp.name AS product_name,
                       kc.quantity_per_kit AS quantity_per_kit,
                       CASE WHEN cp.id IS NOT NULL
                             AND cp.product_type = 'physical'
                             AND cp.status = 'published'
                             AND cp.tracking_stock = TRUE
                            THEN COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric))
                            ELSE CAST(0 AS numeric) END AS available_quantity,
                       CASE WHEN cp.id IS NOT NULL
                             AND cp.product_type = 'physical'
                             AND cp.status = 'published'
                             AND cp.tracking_stock = TRUE
                            THEN FLOOR(GREATEST(COALESCE(SUM(b.quantity - b.reserved_quantity), CAST(0 AS numeric)),
                                                CAST(0 AS numeric)) / kc.quantity_per_kit)
                            ELSE CAST(0 AS numeric) END AS kit_capacity
                FROM product_kit_components kc
                LEFT JOIN products cp
                  ON cp.tenant_id = kc.tenant_id AND cp.id = kc.component_product_id
                LEFT JOIN inventory_balances b
                  ON b.tenant_id = kc.tenant_id
                 AND b.product_id = kc.component_product_id
                 AND b.branch_id = :branchId
                WHERE kc.tenant_id = :tenantId
                  AND kc.kit_product_id = :kitProductId
                GROUP BY kc.id, kc.component_product_id, kc.quantity_per_kit,
                         cp.id, cp.sku, cp.name, cp.product_type, cp.status, cp.tracking_stock
            )
            SELECT component_product_id AS "componentProductId", sku AS sku, product_name AS "productName",
                   quantity_per_kit AS "quantityPerKit", available_quantity AS "availableQuantity",
                   kit_capacity AS "kitCapacity"
            FROM component_availability
            ORDER BY kit_capacity ASC, LOWER(product_name) ASC NULLS LAST, component_product_id ASC
            """, nativeQuery = true)
    List<KitComponentAvailabilityProjection> findKitComponentAvailability(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("kitProductId") UUID kitProductId);

    interface KitComponentAvailabilityProjection {
        UUID getComponentProductId();
        String getSku();
        String getProductName();
        BigDecimal getQuantityPerKit();
        BigDecimal getAvailableQuantity();
        BigDecimal getKitCapacity();
    }

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
        String getProductType();
        String getInventoryMode();
        String getDisplayStatus();
        UUID getInventoryUnitId();
        UUID getSaleUnitId();
        BigDecimal getInventoryToBaseFactor();
        BigDecimal getSaleToBaseFactor();
    }

    interface InventoryStockSummaryProjection {
        long getActiveProducts();
        long getLowStock();
        long getExpiringSoonProducts();
        long getOutOfStock();
    }
}
