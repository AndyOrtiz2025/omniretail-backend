package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryTransferItemRepository
        extends JpaRepository<InventoryTransferItem, UUID> {

    Optional<InventoryTransferItem> findByTenantIdAndId(UUID tenantId, UUID id);

    List<InventoryTransferItem> findByTenantIdAndTransferIdOrderByIdAsc(
            UUID tenantId, UUID transferId);

    Optional<InventoryTransferItem> findByTenantIdAndSourceRequestId(
            UUID tenantId, UUID sourceRequestId);

    List<InventoryTransferItem> findByTenantIdAndSourceRequestIdIn(
            UUID tenantId, Collection<UUID> sourceRequestIds);

    List<InventoryTransferItem> findByTenantIdAndTransferIdInOrderByTransferIdAscIdAsc(
            UUID tenantId, Collection<UUID> transferIds);

    @Query("""
            select (count(item) > 0)
            from InventoryTransferItem item, InventoryTransfer transfer
            where item.tenantId = :tenantId
              and item.productId = :productId
              and transfer.tenantId = item.tenantId
              and transfer.id = item.transferId
              and transfer.status = com.omniretail.backend.inventory.entity.InventoryTransferStatus.inTransit
              and item.dispatchedQuantity > item.receivedQuantity
            """)
    boolean existsPendingInTransitByTenantIdAndProductId(
            @Param("tenantId") UUID tenantId, @Param("productId") UUID productId);
}
