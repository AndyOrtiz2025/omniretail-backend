package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.logistics.entity.PickingPriority;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record PickingQueueResponse(
        UUID pickingOrderId,
        UUID orderId,
        String orderReference,
        String customerName,
        JsonNode storePickupContact,
        DeliveryMethod deliveryMethod,
        UUID branchId,
        PickingStatus status,
        PickingPriority priority,
        UUID assignedUserId,
        PickingProgressResponse progress,
        Instant startedAt,
        Instant createdAt,
        Instant updatedAt,
        PickingSourceType sourceType,
        UUID sourceId,
        String sourceReference) {

    public PickingQueueResponse(
            UUID pickingOrderId,
            UUID orderId,
            String orderReference,
            String customerName,
            JsonNode storePickupContact,
            DeliveryMethod deliveryMethod,
            UUID branchId,
            PickingStatus status,
            PickingPriority priority,
            UUID assignedUserId,
            PickingProgressResponse progress,
            Instant startedAt,
            Instant createdAt,
            Instant updatedAt) {
        this(
                pickingOrderId,
                orderId,
                orderReference,
                customerName,
                storePickupContact,
                deliveryMethod,
                branchId,
                status,
                priority,
                assignedUserId,
                progress,
                startedAt,
                createdAt,
                updatedAt,
                PickingSourceType.order,
                orderId,
                orderReference);
    }
}
