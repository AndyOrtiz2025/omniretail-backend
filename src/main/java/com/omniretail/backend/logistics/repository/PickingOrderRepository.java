package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickingOrderRepository extends JpaRepository<PickingOrder, UUID> {

    Optional<PickingOrder> findByTenantIdAndBranchIdAndId(
            UUID tenantId, UUID branchId, UUID id);

    Optional<PickingOrder> findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
            UUID tenantId, UUID branchId, PickingSourceType sourceType, UUID sourceId);

    Optional<PickingOrder> findByTenantIdAndSourceTypeAndSourceId(
            UUID tenantId, PickingSourceType sourceType, UUID sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select picking from PickingOrder picking
            where picking.tenantId = :tenantId
              and picking.branchId = :branchId
              and picking.id = :id
            """)
    Optional<PickingOrder> findByScopeAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("id") UUID id);

    List<PickingOrder> findByTenantIdAndBranchIdAndStatusInOrderByCreatedAtAsc(
            UUID tenantId, UUID branchId, Collection<PickingStatus> statuses);

    List<PickingOrder> findByTenantIdAndBranchIdAndSourceTypeAndStatusInOrderByCreatedAtAsc(
            UUID tenantId,
            UUID branchId,
            PickingSourceType sourceType,
            Collection<PickingStatus> statuses);

    List<PickingOrder> findByTenantIdAndBranchIdAndAssignedUserIdOrderByUpdatedAtDesc(
            UUID tenantId, UUID branchId, UUID assignedUserId);
}
