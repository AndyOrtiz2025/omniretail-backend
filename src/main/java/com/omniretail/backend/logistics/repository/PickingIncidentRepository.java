package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingIncident;
import com.omniretail.backend.logistics.entity.PickingIncidentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingIncidentRepository extends JpaRepository<PickingIncident, UUID> {

    Optional<PickingIncident> findByTenantIdAndBranchIdAndId(
            UUID tenantId, UUID branchId, UUID id);

    List<PickingIncident> findByTenantIdAndBranchIdAndPickingOrderIdOrderByCreatedAtAsc(
            UUID tenantId, UUID branchId, UUID pickingOrderId);

    boolean existsByTenantIdAndBranchIdAndPickingOrderIdAndStatus(
            UUID tenantId,
            UUID branchId,
            UUID pickingOrderId,
            PickingIncidentStatus status);
}
