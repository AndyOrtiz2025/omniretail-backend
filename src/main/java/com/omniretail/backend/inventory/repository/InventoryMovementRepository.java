package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {

    @Query("""
            SELECT movement
            FROM InventoryMovement movement
            WHERE movement.tenantId = :tenantId
              AND (:branchId IS NULL OR movement.branchId = :branchId)
              AND (:productId IS NULL OR movement.productId = :productId)
              AND (:type IS NULL OR movement.type = :type)
              AND movement.createdAt >= COALESCE(:from, movement.createdAt)
              AND movement.createdAt <= COALESCE(:to, movement.createdAt)
            """)
    Page<InventoryMovement> search(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("type") InventoryMovementType type,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Query("""
            SELECT movement
            FROM InventoryMovement movement
            WHERE movement.tenantId = :tenantId
              AND movement.branchId IN :allowedBranchIds
              AND (:productId IS NULL OR movement.productId = :productId)
              AND (:type IS NULL OR movement.type = :type)
              AND movement.createdAt >= COALESCE(:from, movement.createdAt)
              AND movement.createdAt <= COALESCE(:to, movement.createdAt)
            """)
    Page<InventoryMovement> searchForBranches(
            @Param("tenantId") UUID tenantId,
            @Param("allowedBranchIds") Collection<UUID> allowedBranchIds,
            @Param("productId") UUID productId,
            @Param("type") InventoryMovementType type,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    List<InventoryMovement> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
