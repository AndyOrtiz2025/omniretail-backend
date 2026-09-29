package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(UUID id, PaymentMethod method, BigDecimal amount, String reference) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getMethod(), payment.getAmount(), payment.getReference());
    }
}
