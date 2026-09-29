package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
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

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Optional<PurchaseOrder> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select purchaseOrder from PurchaseOrder purchaseOrder "
            + "where purchaseOrder.tenantId = :tenantId and purchaseOrder.id = :id")
    Optional<PurchaseOrder> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            select purchaseOrder from PurchaseOrder purchaseOrder
            where purchaseOrder.tenantId = :tenantId
              and (:branchId is null or purchaseOrder.branchId = :branchId)
              and (:supplierId is null or purchaseOrder.supplierId = :supplierId)
              and (:status is null or purchaseOrder.status = :status)
            """)
    Page<PurchaseOrder> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("supplierId") UUID supplierId,
            @Param("status") PurchaseOrderStatus status,
            Pageable pageable);

    @Query("""
            select purchaseOrder from PurchaseOrder purchaseOrder
            where purchaseOrder.tenantId = :tenantId
              and purchaseOrder.branchId in :allowedBranchIds
              and (:branchId is null or purchaseOrder.branchId = :branchId)
              and (:supplierId is null or purchaseOrder.supplierId = :supplierId)
              and (:status is null or purchaseOrder.status = :status)
            """)
    Page<PurchaseOrder> findPageForBranches(
            @Param("tenantId") UUID tenantId,
            @Param("allowedBranchIds") Collection<UUID> allowedBranchIds,
            @Param("branchId") UUID branchId,
            @Param("supplierId") UUID supplierId,
            @Param("status") PurchaseOrderStatus status,
            Pageable pageable);
}
