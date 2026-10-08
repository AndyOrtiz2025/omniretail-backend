package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.entity.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleReturnEligibilityResponse(
        Sale sale,
        List<Item> items,
        List<Payment> payments,
        BigDecimal previouslyReturnedAmount,
        BigDecimal cashRefundRecordedAmount,
        boolean originalCashShiftOpen,
        boolean actorHasOpenCashShift,
        AllowedOperations allowedOperations) {

    public record Sale(
            UUID id,
            String documentNumber,
            Instant createdAt,
            String customerDisplayName,
            BigDecimal total,
            SaleStatus status) {}

    public record Item(
            UUID saleItemId,
            UUID productId,
            String sku,
            String name,
            BigDecimal soldQuantity,
            BigDecimal returnedQuantity,
            BigDecimal returnableQuantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal,
            boolean canReturn,
            String blockedReason,
            List<TraceOption> traceOptions) {}

    public record TraceOption(
            UUID productId,
            UUID locationId,
            UUID lotId,
            String lotNumber,
            BigDecimal returnableQuantity,
            List<String> serialNumbers) {}

    public record Payment(
            UUID id,
            PaymentMethod method,
            PaymentStatus status,
            BigDecimal amount,
            String currency) {}

    public record AllowedOperations(
            boolean voidTotal,
            boolean partialReturn,
            String voidBlockedReason,
            String returnBlockedReason) {}
}
