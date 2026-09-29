package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GoodsReceiptResponse(
        UUID id,
        UUID branchId,
        UUID purchaseOrderId,
        String purchaseOrderNumber,
        String number,
        GoodsReceiptStatus status,
        Instant receivedAt,
        String notes,
        UUID receivedByUserId,
        Instant createdAt,
        Instant updatedAt,
        List<GoodsReceiptItemResponse> items) {}
