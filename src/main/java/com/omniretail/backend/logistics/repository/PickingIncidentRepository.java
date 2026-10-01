package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingIncident;
import com.omniretail.backend.logistics.entity.PickingIncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickingIncidentRepository extends JpaRepository<PickingIncident, UUID> {

    Optional<PickingIncident> findByTenantIdAndBranchIdAndId(
            UUID tenantId, UUID branchId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select incident from PickingIncident incident
            where incident.tenantId = :tenantId
              and incident.branchId = :branchId
              and incident.pickingOrderId = :pickingOrderId
              and incident.id = :id
            """)
    Optional<PickingIncident> findByScopeAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("pickingOrderId") UUID pickingOrderId,
            @Param("id") UUID id);

    List<PickingIncident> findByTenantIdAndBranchIdAndPickingOrderIdOrderByCreatedAtAsc(
            UUID tenantId, UUID branchId, UUID pickingOrderId);

    boolean existsByTenantIdAndBranchIdAndPickingOrderIdAndStatus(
            UUID tenantId,
            UUID branchId,
            UUID pickingOrderId,
            PickingIncidentStatus status);
}
