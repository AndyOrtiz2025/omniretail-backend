package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryBalance;
import jakarta.persistence.LockModeType;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryBalance> findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
            UUID tenantId, UUID branchId, UUID productId);

    List<InventoryBalance> findByTenantId(UUID tenantId);

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
}
