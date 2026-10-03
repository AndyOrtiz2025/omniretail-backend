package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryMovementTrace;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryMovementTraceRepository extends JpaRepository<InventoryMovementTrace, UUID> {

    List<InventoryMovementTrace> findByTenantIdAndMovementIdOrderByIdAsc(
            UUID tenantId, UUID movementId);

    List<InventoryMovementTrace> findByTenantIdAndMovementIdInOrderByMovementIdAscIdAsc(
            UUID tenantId, Collection<UUID> movementIds);
}
