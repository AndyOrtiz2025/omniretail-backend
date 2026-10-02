package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import jakarta.persistence.LockModeType;
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
}
