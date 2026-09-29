package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.GoodsReceipt;
import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    Optional<GoodsReceipt> findByTenantIdAndId(UUID tenantId, UUID id);

    @Modifying
    long deleteByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from GoodsReceipt receipt where receipt.tenantId = :tenantId and receipt.id = :id")
    Optional<GoodsReceipt> findForUpdateByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            select receipt from GoodsReceipt receipt
            where receipt.tenantId = :tenantId
              and (:branchId is null or receipt.branchId = :branchId)
              and (:purchaseOrderId is null or receipt.purchaseOrderId = :purchaseOrderId)
              and (:status is null or receipt.status = :status)
            """)
    Page<GoodsReceipt> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("purchaseOrderId") UUID purchaseOrderId,
            @Param("status") GoodsReceiptStatus status,
            Pageable pageable);

    @Query("""
            select receipt from GoodsReceipt receipt
            where receipt.tenantId = :tenantId
              and receipt.branchId in :allowedBranchIds
              and (:branchId is null or receipt.branchId = :branchId)
              and (:purchaseOrderId is null or receipt.purchaseOrderId = :purchaseOrderId)
              and (:status is null or receipt.status = :status)
            """)
    Page<GoodsReceipt> findPageForBranches(
            @Param("tenantId") UUID tenantId,
            @Param("allowedBranchIds") Collection<UUID> allowedBranchIds,
            @Param("branchId") UUID branchId,
            @Param("purchaseOrderId") UUID purchaseOrderId,
            @Param("status") GoodsReceiptStatus status,
            Pageable pageable);
}
