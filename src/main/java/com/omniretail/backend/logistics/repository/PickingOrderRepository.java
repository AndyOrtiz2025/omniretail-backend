package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
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
              and picking.sourceType = :sourceType
              and picking.sourceId = :sourceId
            """)
    Optional<PickingOrder> findByTenantIdAndSourceTypeAndSourceIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("sourceType") PickingSourceType sourceType,
            @Param("sourceId") UUID sourceId);

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

    @Query("""
            select picking from PickingOrder picking
            where picking.tenantId = :tenantId
              and picking.branchId = :branchId
              and picking.status in :pickingStatuses
              and (
                (picking.sourceType = :orderSourceType and exists (
                  select sourceOrder.id from Order sourceOrder
                  where sourceOrder.tenantId = :tenantId
                    and sourceOrder.branchId = :branchId
                    and sourceOrder.id = picking.sourceId
                    and sourceOrder.status in :orderStatuses
                ))
                or
                (picking.sourceType = :transferSourceType and exists (
                  select transfer.id from InventoryTransfer transfer
                  where transfer.tenantId = :tenantId
                    and transfer.sourceBranchId = :branchId
                    and transfer.id = picking.sourceId
                    and transfer.status = :transferStatus
                ))
              )
            order by picking.createdAt asc
            """)
    List<PickingOrder> findOperationalQueue(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("pickingStatuses") Collection<PickingStatus> pickingStatuses,
            @Param("orderSourceType") PickingSourceType orderSourceType,
            @Param("orderStatuses") Collection<OrderStatus> orderStatuses,
            @Param("transferSourceType") PickingSourceType transferSourceType,
            @Param("transferStatus") InventoryTransferStatus transferStatus);

    List<PickingOrder> findByTenantIdAndBranchIdAndSourceTypeAndStatusInOrderByCreatedAtAsc(
            UUID tenantId,
            UUID branchId,
            PickingSourceType sourceType,
            Collection<PickingStatus> statuses);

    List<PickingOrder> findByTenantIdAndBranchIdAndAssignedUserIdOrderByUpdatedAtDesc(
            UUID tenantId, UUID branchId, UUID assignedUserId);
}
