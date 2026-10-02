package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryTransferReceiptItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTransferReceiptItemRepository
        extends JpaRepository<InventoryTransferReceiptItem, UUID> {

    Optional<InventoryTransferReceiptItem> findByTenantIdAndId(UUID tenantId, UUID id);

    List<InventoryTransferReceiptItem> findByTenantIdAndReceiptIdOrderByIdAsc(
            UUID tenantId, UUID receiptId);
}
