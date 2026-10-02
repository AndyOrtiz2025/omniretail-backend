package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingAssignmentRelease;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingAssignmentReleaseRepository
        extends JpaRepository<PickingAssignmentRelease, UUID> {

    List<PickingAssignmentRelease> findByTenantIdAndBranchIdAndPickingOrderIdOrderByReleasedAtAsc(
            UUID tenantId, UUID branchId, UUID pickingOrderId);
}
