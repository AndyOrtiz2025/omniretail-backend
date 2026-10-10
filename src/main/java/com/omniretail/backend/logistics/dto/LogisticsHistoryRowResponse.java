package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.logistics.entity.DispatchStatus;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LogisticsHistoryRowResponse(
        PickingSourceType sourceType,
        UUID sourceId,
        UUID orderId,
        String orderReference,
        LogisticsHistoryDeliveryMethod deliveryMethod,
        String operationalStatus,
        String contactName,
        String contactPhone,
        UUID pickingOrderId,
        UUID packingId,
        UUID dispatchId,
        UUID storePickupDeliveryId,
        Instant pickingCompletedAt,
        Instant packingFinalizedAt,
        Instant dispatchedAt,
        Instant deliveredAt,
        UUID responsibleUserId,
        String responsibleUserName,
        BigDecimal totalWeight,
        Integer packageCount,
        DispatchStatus dispatchStatus,
        String carrierName,
        String trackingNumber) {}
