package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID saleId,
        UUID orderId,
        PaymentMethod method,
        BigDecimal amount,
        String reference,
        PaymentStatus status,
        String currency,
        UUID bankAccountId,
        Boolean externallyVerified,
        UUID verifiedByUserId,
        Instant verifiedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getSaleId(),
                payment.getOrderId(),
                payment.getMethod(),
                payment.getAmount(),
                payment.getReference(),
                payment.getStatus(),
                payment.getCurrency(),
                payment.getBankAccountId(),
                payment.getExternallyVerified(),
                payment.getVerifiedByUserId(),
                payment.getVerifiedAt());
    }
}
