package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record PackingQueueResponse(
        UUID packingId,
        UUID orderId,
        String orderReference,
        String customerName,
        JsonNode storePickupContact,
        DeliveryMethod deliveryMethod,
        PackingSourceType sourceType,
        UUID sourceId,
        PackingStatus status,
        Long version,
        Instant startedAt,
        Instant updatedAt) {}
