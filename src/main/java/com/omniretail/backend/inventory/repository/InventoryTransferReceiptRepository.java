package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryTransferReceipt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTransferReceiptRepository
        extends JpaRepository<InventoryTransferReceipt, UUID> {

    Optional<InventoryTransferReceipt> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InventoryTransferReceipt> findByTenantIdAndConfirmationId(
            UUID tenantId, String confirmationId);

    List<InventoryTransferReceipt> findByTenantIdAndTransferIdOrderByReceivedAtAsc(
            UUID tenantId, UUID transferId);
}
