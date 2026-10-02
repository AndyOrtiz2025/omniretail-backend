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
        Instant updatedAt,
        String sourceReference) {

    public PackingQueueResponse(
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
            Instant updatedAt) {
        this(
                packingId,
                orderId,
                orderReference,
                customerName,
                storePickupContact,
                deliveryMethod,
                sourceType,
                sourceId,
                status,
                version,
                startedAt,
                updatedAt,
                orderReference);
    }
}
