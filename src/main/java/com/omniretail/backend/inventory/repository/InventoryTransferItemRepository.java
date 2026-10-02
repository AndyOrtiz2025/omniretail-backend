package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTransferItemRepository
        extends JpaRepository<InventoryTransferItem, UUID> {

    Optional<InventoryTransferItem> findByTenantIdAndId(UUID tenantId, UUID id);

    List<InventoryTransferItem> findByTenantIdAndTransferIdOrderByIdAsc(
            UUID tenantId, UUID transferId);
}
