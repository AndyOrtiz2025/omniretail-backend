package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.logistics.entity.PickingStatus;
import java.time.Instant;
import java.util.UUID;

public record PickingActionResponse(
        UUID pickingOrderId,
        UUID orderId,
        PickingStatus status,
        UUID assignedUserId,
        OrderStatus orderStatus,
        Instant updatedAt,
        boolean idempotent) {}
