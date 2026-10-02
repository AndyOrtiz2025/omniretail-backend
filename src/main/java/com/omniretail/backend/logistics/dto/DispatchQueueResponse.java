package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.logistics.entity.DispatchSourceType;
import java.time.Instant;
import java.util.UUID;

public record DispatchQueueResponse(
        UUID orderId,
        String orderReference,
        Instant createdAt,
        TransportMode transportMode,
        UUID packingId,
        Instant packingFinalizedAt,
        DispatchSourceType sourceType,
        UUID sourceId,
        String sourceReference) {

    public DispatchQueueResponse(
            UUID orderId,
            String orderReference,
            Instant createdAt,
            TransportMode transportMode,
            UUID packingId,
            Instant packingFinalizedAt) {
        this(
                orderId,
                orderReference,
                createdAt,
                transportMode,
                packingId,
                packingFinalizedAt,
                DispatchSourceType.order,
                orderId,
                orderReference);
    }
}
