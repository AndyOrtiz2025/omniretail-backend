package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryTransferRepository extends JpaRepository<InventoryTransfer, UUID> {

    Optional<InventoryTransfer> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InventoryTransfer> findByTenantIdAndNumber(UUID tenantId, String number);

    Optional<InventoryTransfer> findByTenantIdAndOperationId(UUID tenantId, String operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select transfer from InventoryTransfer transfer
            where transfer.tenantId = :tenantId and transfer.id = :id
            """)
    Optional<InventoryTransfer> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            select transfer from InventoryTransfer transfer
            where transfer.tenantId = :tenantId
              and (:sourceBranchId is null or transfer.sourceBranchId = :sourceBranchId)
              and (:destinationBranchId is null
                   or transfer.destinationBranchId = :destinationBranchId)
              and (:status is null or transfer.status = :status)
            """)
    Page<InventoryTransfer> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("sourceBranchId") UUID sourceBranchId,
            @Param("destinationBranchId") UUID destinationBranchId,
            @Param("status") InventoryTransferStatus status,
            Pageable pageable);
}
