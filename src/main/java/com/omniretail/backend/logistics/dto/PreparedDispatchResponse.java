package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.entity.PickingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Snapshot de solo lectura utilizado antes de confirmar la salida de un pedido preparado. */
public record PreparedDispatchResponse(
        UUID orderId,
        String orderReference,
        Instant createdAt,
        OrderStatus orderStatus,
        String recipientName,
        String recipientPhone,
        JsonNode deliveryAddress,
        JsonNode notificationContact,
        TransportMode transportMode,
        UUID pickingOrderId,
        PickingStatus pickingStatus,
        Instant pickingCompletedAt,
        UUID packingId,
        PackingStatus packingStatus,
        Instant packingFinalizedAt,
        Integer packageCount,
        BigDecimal totalWeight,
        String labelCode) {}
