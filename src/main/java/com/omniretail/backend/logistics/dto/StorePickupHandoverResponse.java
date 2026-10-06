package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record StorePickupHandoverResponse(
        UUID orderId,
        OrderStatus status,
        Instant deliveredAt,
        boolean idempotent) {}
