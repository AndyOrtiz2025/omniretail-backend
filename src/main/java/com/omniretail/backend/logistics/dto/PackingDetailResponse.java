package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record PackingDetailResponse(
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
        UUID pickingOrderId,
        OrderStatus orderStatus,
        JsonNode deliveryAddress,
        PackingChecklistResponse checklist,
        BigDecimal totalWeight,
        Integer packageCount,
        String labelGenerationId,
        String labelCode,
        Instant labelGeneratedAt,
        Instant labelPrintedAt,
        Instant finalizedAt,
        List<PackingPreparedContentResponse> preparedContents) {}
