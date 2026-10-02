package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryTransferRequestRepository
        extends JpaRepository<InventoryTransferRequest, UUID> {

    Optional<InventoryTransferRequest> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from InventoryTransferRequest request
            where request.tenantId = :tenantId and request.id = :id
            """)
    Optional<InventoryTransferRequest> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            select request from InventoryTransferRequest request
            where request.tenantId = :tenantId
              and (:requestingBranchId is null
                   or request.requestingBranchId = :requestingBranchId)
              and (:sourceBranchId is null or request.sourceBranchId = :sourceBranchId)
              and (:status is null or request.status = :status)
            """)
    Page<InventoryTransferRequest> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("requestingBranchId") UUID requestingBranchId,
            @Param("sourceBranchId") UUID sourceBranchId,
            @Param("status") InventoryTransferRequestStatus status,
            Pageable pageable);

    @Query("""
            select request from InventoryTransferRequest request
            where request.tenantId = :tenantId
              and (request.requestingBranchId in :branchIds
                   or request.sourceBranchId in :branchIds)
              and (:requestingBranchId is null
                   or request.requestingBranchId = :requestingBranchId)
              and (:sourceBranchId is null or request.sourceBranchId = :sourceBranchId)
              and (:status is null or request.status = :status)
            """)
    Page<InventoryTransferRequest> findPageForBranches(
            @Param("tenantId") UUID tenantId,
            @Param("branchIds") Collection<UUID> branchIds,
            @Param("requestingBranchId") UUID requestingBranchId,
            @Param("sourceBranchId") UUID sourceBranchId,
            @Param("status") InventoryTransferRequestStatus status,
            Pageable pageable);
}
