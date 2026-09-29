package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
        UUID id,
        UUID branchId,
        String number,
        UUID supplierId,
        String supplierName,
        PurchaseOrderStatus status,
        LocalDate expectedDate,
        String notes,
        BigDecimal subtotal,
        BigDecimal total,
        UUID createdByUserId,
        UUID approvedByUserId,
        Instant approvedAt,
        String cancellationReason,
        UUID cancelledByUserId,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt,
        List<PurchaseOrderItemResponse> items) {}
