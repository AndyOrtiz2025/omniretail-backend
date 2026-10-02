package com.omniretail.backend.logistics.repository;
import com.omniretail.backend.logistics.entity.DispatchOperation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DispatchOperationRepository extends JpaRepository<DispatchOperation, UUID> { Optional<DispatchOperation> findByTenantIdAndOperationId(UUID tenantId, String operationId); }
