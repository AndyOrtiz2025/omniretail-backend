package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryBalance;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Page<InventoryBalance> findByTenantIdAndBranchId(
            UUID tenantId, UUID branchId, Pageable pageable);

    @Modifying
    @Query(
            value = """
                    INSERT INTO inventory_balances
                        (tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                    VALUES (:tenantId, :branchId, :productId, NULL, 0, 0)
                    ON CONFLICT ON CONSTRAINT uk_inventory_balances_logical DO NOTHING
                    """,
            nativeQuery = true)
    void ensureDefaultLocationBalanceExists(UUID tenantId, UUID branchId, UUID productId);

    @Modifying
    @Query(
            value = """
                    INSERT INTO inventory_balances
                        (tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                    VALUES (:tenantId, :branchId, :productId, :locationId, 0, 0)
                    ON CONFLICT ON CONSTRAINT uk_inventory_balances_logical DO NOTHING
                    """,
            nativeQuery = true)
    void ensureLocationBalanceExists(
            UUID tenantId, UUID branchId, UUID productId, UUID locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryBalance> findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
            UUID tenantId, UUID branchId, UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryBalance> findByTenantIdAndBranchIdAndProductIdAndLocationId(
            UUID tenantId, UUID branchId, UUID productId, UUID locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryBalance> findByTenantIdAndId(UUID tenantId, UUID id);

    List<InventoryBalance> findByTenantId(UUID tenantId);

    List<InventoryBalance> findByTenantIdAndBranchIdAndLocationIdIsNull(UUID tenantId, UUID branchId);

    List<InventoryBalance> findByTenantIdAndBranchIdAndProductId(
            UUID tenantId, UUID branchId, UUID productId);

    /** Todos los balances de la sucursal (solo lectura, sin bloqueo). */
    List<InventoryBalance> findByTenantIdAndBranchId(UUID tenantId, UUID branchId);

    @Query(
            value = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM inventory_balances
                        WHERE tenant_id = :tenantId
                          AND location_id = :locationId
                          AND (quantity > 0 OR reserved_quantity > 0)
                    )
                    """,
            nativeQuery = true)
    boolean existsPositiveStockByTenantIdAndLocationId(
            @Param("tenantId") UUID tenantId,
            @Param("locationId") UUID locationId);

    @Query(
            value = """
                    SELECT EXISTS (
                        SELECT 1 FROM inventory_balances
                        WHERE tenant_id = :tenantId
                          AND product_id = :productId
                          AND (quantity > 0 OR reserved_quantity > 0)
                    )
                    """,
            nativeQuery = true)
    boolean existsPositiveStockByTenantIdAndProductId(
            @Param("tenantId") UUID tenantId, @Param("productId") UUID productId);

    @Query(
            value = """
                    SELECT branch.id AS "branchId",
                           branch.name AS "branchName",
                           COALESCE(
                               SUM(balance.quantity - balance.reserved_quantity),
                               CAST(0 AS numeric)
                           ) AS "availableQuantity"
                    FROM branches branch
                    LEFT JOIN inventory_balances balance
                      ON balance.tenant_id = branch.tenant_id
                     AND balance.branch_id = branch.id
                     AND balance.product_id = :productId
                    WHERE branch.tenant_id = :tenantId
                      AND branch.id <> :excludedBranchId
                    GROUP BY branch.id, branch.name
                    ORDER BY LOWER(branch.name) ASC, branch.id ASC
                    """,
            nativeQuery = true)
    List<CrossBranchStockProjection> findCrossBranchStock(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("excludedBranchId") UUID excludedBranchId);

    @Query(
            value = """
                    SELECT branch.id AS "branchId",
                           branch.name AS "branchName",
                           COALESCE(
                               SUM(balance.quantity - balance.reserved_quantity),
                               CAST(0 AS numeric)
                           ) AS "availableQuantity"
                    FROM branches branch
                    LEFT JOIN inventory_balances balance
                      ON balance.tenant_id = branch.tenant_id
                     AND balance.branch_id = branch.id
                     AND balance.product_id = :productId
                    WHERE branch.tenant_id = :tenantId
                      AND branch.id IN (:branchIds)
                      AND branch.id <> :excludedBranchId
                    GROUP BY branch.id, branch.name
                    ORDER BY LOWER(branch.name) ASC, branch.id ASC
                    """,
            nativeQuery = true)
    List<CrossBranchStockProjection> findCrossBranchStockIn(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("excludedBranchId") UUID excludedBranchId,
            @Param("branchIds") Collection<UUID> branchIds);

    interface CrossBranchStockProjection {
        UUID getBranchId();

        String getBranchName();

        BigDecimal getAvailableQuantity();
    }
}
