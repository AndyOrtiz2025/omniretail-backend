package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickingItemRepository extends JpaRepository<PickingItem, UUID> {

    @Query("""
            select item from PickingItem item, PickingOrder picking
            where item.id = :id
              and item.tenantId = :tenantId
              and picking.id = item.pickingOrderId
              and picking.tenantId = :tenantId
              and picking.branchId = :branchId
            """)
    Optional<PickingItem> findByScopeAndId(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("id") UUID id);

    @Query("""
            select item from PickingItem item, PickingOrder picking
            where item.tenantId = :tenantId
              and item.pickingOrderId = :pickingOrderId
              and picking.id = item.pickingOrderId
              and picking.tenantId = :tenantId
              and picking.branchId = :branchId
            order by item.createdAt asc
            """)
    List<PickingItem> findByScopeAndPickingOrderId(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("pickingOrderId") UUID pickingOrderId);

    @Query("""
            select item from PickingItem item, PickingOrder picking
            where item.tenantId = :tenantId
              and item.pickingOrderId = :pickingOrderId
              and item.sourceLineId = :sourceLineId
              and item.productId = :productId
              and picking.id = item.pickingOrderId
              and picking.tenantId = :tenantId
              and picking.branchId = :branchId
            """)
    Optional<PickingItem> findByScopeAndSourceLine(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("pickingOrderId") UUID pickingOrderId,
            @Param("sourceLineId") UUID sourceLineId,
            @Param("productId") UUID productId);
}
