package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PackingOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackingOperationRepository extends JpaRepository<PackingOperation, UUID> {

    Optional<PackingOperation> findByTenantIdAndOperationId(UUID tenantId, String operationId);
}
