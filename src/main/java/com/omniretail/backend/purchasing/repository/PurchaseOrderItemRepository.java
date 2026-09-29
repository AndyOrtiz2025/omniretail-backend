package com.omniretail.backend.purchasing.repository;

import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, UUID> {

    List<PurchaseOrderItem> findByTenantIdAndPurchaseOrderIdOrderByIdAsc(
            UUID tenantId, UUID purchaseOrderId);

    @Query("""
            select item from PurchaseOrderItem item
            where item.tenantId = :tenantId and item.purchaseOrderId in :purchaseOrderIds
            order by item.purchaseOrderId asc, item.id asc
            """)
    List<PurchaseOrderItem> findForOrders(
            @Param("tenantId") UUID tenantId,
            @Param("purchaseOrderIds") Collection<UUID> purchaseOrderIds);

    @Modifying
    long deleteByTenantIdAndPurchaseOrderId(UUID tenantId, UUID purchaseOrderId);
}
