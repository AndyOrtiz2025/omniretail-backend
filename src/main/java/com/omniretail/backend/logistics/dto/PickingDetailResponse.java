package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.logistics.entity.PickingPriority;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record PickingDetailResponse(
        UUID pickingOrderId,
        UUID orderId,
        String orderReference,
        String customerName,
        JsonNode storePickupContact,
        DeliveryMethod deliveryMethod,
        PickingSourceType sourceType,
        UUID sourceId,
        UUID branchId,
        PickingStatus status,
        PickingPriority priority,
        UUID assignedUserId,
        PickingProgressResponse progress,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        List<PickingLineResponse> lines,
        List<PickingIncidentResponse> incidents,
        List<PickingReleaseResponse> releases) {}
