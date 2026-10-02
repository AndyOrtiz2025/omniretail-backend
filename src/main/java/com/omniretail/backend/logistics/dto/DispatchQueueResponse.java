package com.omniretail.backend.logistics.dto;
import com.omniretail.backend.ecommerce.entity.TransportMode; import java.time.Instant; import java.util.UUID;
public record DispatchQueueResponse(UUID orderId, String orderReference, Instant createdAt, TransportMode transportMode, UUID packingId, Instant packingFinalizedAt) {}
