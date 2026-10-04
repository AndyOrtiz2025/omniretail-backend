package com.omniretail.backend.pos.dto;

import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingPriority;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import java.time.Instant;
import java.util.UUID;

public record PosPickingOrderResponse(
        UUID id,
        UUID tenantId,
        UUID branchId,
        UUID orderId,
        PickingSourceType sourceType,
        UUID sourceId,
        UUID assignedUserId,
        PickingStatus status,
        PickingPriority priority,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static PosPickingOrderResponse from(PickingOrder picking) {
        return new PosPickingOrderResponse(
                picking.getId(),
                picking.getTenantId(),
                picking.getBranchId(),
                picking.getOrderId(),
                picking.getSourceType(),
                picking.getSourceId(),
                picking.getAssignedUserId(),
                picking.getStatus(),
                picking.getPriority(),
                picking.getStartedAt(),
                picking.getCompletedAt(),
                picking.getCreatedAt(),
                picking.getUpdatedAt());
    }
}
