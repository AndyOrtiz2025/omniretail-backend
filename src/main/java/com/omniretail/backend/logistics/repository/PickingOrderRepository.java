package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingOrderRepository extends JpaRepository<PickingOrder, UUID> {

    Optional<PickingOrder> findByTenantIdAndBranchIdAndId(
            UUID tenantId, UUID branchId, UUID id);

    Optional<PickingOrder> findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
            UUID tenantId, UUID branchId, PickingSourceType sourceType, UUID sourceId);

    List<PickingOrder> findByTenantIdAndBranchIdAndStatusInOrderByCreatedAtAsc(
            UUID tenantId, UUID branchId, Collection<PickingStatus> statuses);

    List<PickingOrder> findByTenantIdAndBranchIdAndAssignedUserIdOrderByUpdatedAtDesc(
            UUID tenantId, UUID branchId, UUID assignedUserId);
}
