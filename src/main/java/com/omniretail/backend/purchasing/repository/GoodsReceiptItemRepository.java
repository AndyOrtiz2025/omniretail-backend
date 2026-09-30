package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.GoodsReceiptItem;
import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptItemRepository extends JpaRepository<GoodsReceiptItem, UUID> {

    Optional<GoodsReceiptItem> findByTenantIdAndId(UUID tenantId, UUID id);

    List<GoodsReceiptItem> findByTenantIdAndGoodsReceiptIdOrderByIdAsc(
            UUID tenantId, UUID goodsReceiptId);

    @Query("""
            select item from GoodsReceiptItem item
            where item.tenantId = :tenantId and item.goodsReceiptId in :receiptIds
            order by item.goodsReceiptId asc, item.id asc
            """)
    List<GoodsReceiptItem> findForReceipts(
            @Param("tenantId") UUID tenantId,
            @Param("receiptIds") Collection<UUID> receiptIds);

    @Modifying
    long deleteByTenantIdAndGoodsReceiptId(UUID tenantId, UUID goodsReceiptId);

    @Query("""
            select item.purchaseOrderItemId as purchaseOrderItemId,
                   sum(item.receivedQuantity) as receivedQuantity
            from GoodsReceiptItem item, GoodsReceipt receipt
            where item.tenantId = :tenantId
              and receipt.tenantId = :tenantId
              and receipt.id = item.goodsReceiptId
              and receipt.purchaseOrderId = :purchaseOrderId
              and receipt.status = :status
            group by item.purchaseOrderItemId
            """)
    List<ConfirmedQuantity> sumConfirmedByPurchaseOrderItem(
            @Param("tenantId") UUID tenantId,
            @Param("purchaseOrderId") UUID purchaseOrderId,
            @Param("status") GoodsReceiptStatus status);

    interface ConfirmedQuantity {
        UUID getPurchaseOrderItemId();

        BigDecimal getReceivedQuantity();
    }
}
