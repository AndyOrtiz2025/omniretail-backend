package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingItemUpdateOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingItemUpdateOperationRepository
        extends JpaRepository<PickingItemUpdateOperation, UUID> {

    Optional<PickingItemUpdateOperation> findByTenantIdAndOperationId(
            UUID tenantId, String operationId);
}
