package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import java.time.Instant;
import java.util.UUID;

public record PickingActionResponse(
        UUID pickingOrderId,
        UUID orderId,
        PickingStatus status,
        UUID assignedUserId,
        OrderStatus orderStatus,
        Instant updatedAt,
        boolean idempotent,
        PickingSourceType sourceType,
        UUID sourceId,
        String sourceReference) {

    public PickingActionResponse(
            UUID pickingOrderId,
            UUID orderId,
            PickingStatus status,
            UUID assignedUserId,
            OrderStatus orderStatus,
            Instant updatedAt,
            boolean idempotent) {
        this(
                pickingOrderId,
                orderId,
                status,
                assignedUserId,
                orderStatus,
                updatedAt,
                idempotent,
                PickingSourceType.order,
                orderId,
                null);
    }
}
