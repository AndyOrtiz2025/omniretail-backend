package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryLotBalanceRepository extends JpaRepository<InventoryLotBalance, UUID> {

    Optional<InventoryLotBalance> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryLotBalance> findForUpdateByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * true si el producto tiene saldo o reserva de lote en la sucursal fuera de la ubicacion indicada
     * (null = balance sin ubicacion). Solo lectura: no mueve ni consolida nada.
     */
    @Query(
            value = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM inventory_lot_balances lot_balance
                        JOIN inventory_lots lot
                          ON lot.tenant_id = lot_balance.tenant_id AND lot.id = lot_balance.lot_id
                        WHERE lot_balance.tenant_id = :tenantId
                          AND lot_balance.branch_id = :branchId
                          AND lot.product_id = :productId
                          AND (lot_balance.quantity > 0 OR lot_balance.reserved_quantity > 0)
                          AND lot_balance.location_id IS DISTINCT FROM CAST(:locationId AS uuid)
                    )
                    """,
            nativeQuery = true)
    boolean existsStockedOutsideLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId);

    @Modifying
    @Query(
            value = """
                    INSERT INTO inventory_lot_balances
                        (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                    VALUES (:id, :tenantId, :branchId, NULL, :lotId, 0, 0)
                    ON CONFLICT ON CONSTRAINT uk_inventory_lot_balances_logical DO NOTHING
                    """,
            nativeQuery = true)
    void ensureWithoutLocationExists(UUID id, UUID tenantId, UUID branchId, UUID lotId);

    @Modifying
    @Query(
            value = """
                    INSERT INTO inventory_lot_balances
                        (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                    VALUES (:id, :tenantId, :branchId, :locationId, :lotId, 0, 0)
                    ON CONFLICT ON CONSTRAINT uk_inventory_lot_balances_logical DO NOTHING
                    """,
            nativeQuery = true)
    void ensureAtLocationExists(
            UUID id, UUID tenantId, UUID branchId, UUID locationId, UUID lotId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.lotId = :lotId
              and balance.locationId is null
              and lot.id = balance.lotId
              and lot.tenantId = :tenantId
              and lot.productId = :productId
            """)
    Optional<InventoryLotBalance> findForUpdateWithoutLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("lotId") UUID lotId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.lotId = :lotId
              and balance.locationId = :locationId
              and lot.id = balance.lotId
              and lot.tenantId = :tenantId
              and lot.productId = :productId
            """)
    Optional<InventoryLotBalance> findForUpdateAtLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("lotId") UUID lotId,
            @Param("locationId") UUID locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.locationId = :locationId
              and balance.lotId = lot.id
              and lot.tenantId = :tenantId
              and lot.productId = :productId
              and (balance.quantity > 0 or balance.reservedQuantity > 0)
            order by balance.lotId
            """)
    List<InventoryLotBalance> findAllForUpdateAtLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.locationId is null
              and balance.lotId = lot.id
              and lot.tenantId = :tenantId
              and lot.productId = :productId
              and (balance.quantity > 0 or balance.reservedQuantity > 0)
            order by balance.lotId
            """)
    List<InventoryLotBalance> findAllForUpdateWithoutLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId);

    @Query("""
            select balance.id from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.lotId = :lotId
              and balance.locationId = :locationId
              and lot.id = balance.lotId
              and lot.tenantId = :tenantId
              and lot.productId = :productId
            """)
    Optional<UUID> findIdAtLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("lotId") UUID lotId,
            @Param("locationId") UUID locationId);

    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.lotId = lot.id
              and lot.tenantId = :tenantId
              and lot.productId = :productId
            """)
    List<InventoryLotBalance> findByTenantBranchAndProduct(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId);

    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.lotId = lot.id
              and lot.tenantId = :tenantId
              and lot.productId = :productId
              and balance.quantity > balance.reservedQuantity
            order by lot.expirationDate, lot.lotNumber, balance.id
            """)
    List<InventoryLotBalance> findAvailableByTenantBranchAndProduct(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId);

    @Query("""
            select balance from InventoryLotBalance balance, InventoryLot lot
            where balance.tenantId = :tenantId
              and balance.branchId = :branchId
              and balance.locationId = :locationId
              and balance.lotId = lot.id
              and lot.tenantId = :tenantId
              and lot.productId = :productId
              and balance.quantity > balance.reservedQuantity
            order by lot.expirationDate, lot.lotNumber, balance.id
            """)
    List<InventoryLotBalance> findAvailableByTenantBranchProductAndLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId);

    @Query(
            value = """
                    SELECT lot.id AS "lotId",
                           lot.lot_number AS "lotNumber",
                           lot.expiration_date AS "expirationDate",
                           product.id AS "productId",
                           product.sku AS "sku",
                           product.name AS "productName",
                           balance.branch_id AS "branchId",
                           balance.location_id AS "locationId",
                           balance.quantity AS "quantity",
                           balance.reserved_quantity AS "reservedQuantity",
                           balance.quantity - balance.reserved_quantity AS "availableQuantity"
                    FROM inventory_lot_balances balance
                    JOIN inventory_lots lot
                      ON lot.tenant_id = balance.tenant_id
                     AND lot.id = balance.lot_id
                    JOIN products product
                      ON product.tenant_id = lot.tenant_id
                     AND product.id = lot.product_id
                    WHERE balance.tenant_id = :tenantId
                      AND balance.branch_id = :branchId
                      AND balance.quantity > 0
                      AND lot.expiration_date BETWEEN :businessDate AND :limitDate
                      AND product.status = 'published'
                      AND product.product_type = 'physical'
                      AND product.tracking_stock = TRUE
                      AND product.tracking_lot = TRUE
                      AND product.tracking_expiration = TRUE
                      AND (:productId IS NULL OR product.id = :productId)
                      AND (:locationId IS NULL OR balance.location_id = :locationId)
                    ORDER BY lot.expiration_date ASC,
                             LOWER(product.name) ASC,
                             lot.lot_number ASC,
                             balance.location_id ASC NULLS FIRST,
                             balance.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM inventory_lot_balances balance
                    JOIN inventory_lots lot
                      ON lot.tenant_id = balance.tenant_id
                     AND lot.id = balance.lot_id
                    JOIN products product
                      ON product.tenant_id = lot.tenant_id
                     AND product.id = lot.product_id
                    WHERE balance.tenant_id = :tenantId
                      AND balance.branch_id = :branchId
                      AND balance.quantity > 0
                      AND lot.expiration_date BETWEEN :businessDate AND :limitDate
                      AND product.status = 'published'
                      AND product.product_type = 'physical'
                      AND product.tracking_stock = TRUE
                      AND product.tracking_lot = TRUE
                      AND product.tracking_expiration = TRUE
                      AND (:productId IS NULL OR product.id = :productId)
                      AND (:locationId IS NULL OR balance.location_id = :locationId)
                    """,
            nativeQuery = true)
    org.springframework.data.domain.Page<ExpiringLotProjection> findExpiringLots(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("businessDate") LocalDate businessDate,
            @Param("limitDate") LocalDate limitDate,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            org.springframework.data.domain.Pageable pageable);

    interface ExpiringLotProjection {
        UUID getLotId();

        String getLotNumber();

        LocalDate getExpirationDate();

        UUID getProductId();

        String getSku();

        String getProductName();

        UUID getBranchId();

        UUID getLocationId();

        BigDecimal getQuantity();

        BigDecimal getReservedQuantity();

        BigDecimal getAvailableQuantity();
    }
}
