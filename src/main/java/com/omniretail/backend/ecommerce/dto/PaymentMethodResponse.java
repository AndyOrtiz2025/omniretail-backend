package com.omniretail.backend.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.omniretail.backend.ecommerce.entity.CustomerPaymentMethod;
import com.omniretail.backend.pos.entity.PaymentMethod;
import java.time.Instant;
import java.util.UUID;

/**
 * Tarjeta guardada con la forma de {@code CustomerPaymentMethod.ts}, salvo {@code providerPaymentMethodId}:
 * el token del proveedor nunca sale del servidor. {@code status} siempre es {@code active} porque una
 * tarjeta eliminada se borra (no se archiva).
 */
public record PaymentMethodResponse(
        UUID id,
        UUID tenantId,
        UUID customerId,
        PaymentMethod type,
        String brand,
        String issuingBank,
        String last4,
        int expirationMonth,
        int expirationYear,
        String cardholderName,
        @JsonProperty("isDefault") boolean isDefault,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    static final String ACTIVE = "active";

    public static PaymentMethodResponse from(CustomerPaymentMethod method) {
        return new PaymentMethodResponse(
                method.getId(),
                method.getTenantId(),
                method.getCustomerId(),
                method.getType(),
                method.getBrand(),
                method.getIssuingBank(),
                method.getLast4(),
                method.getExpirationMonth(),
                method.getExpirationYear(),
                method.getCardholderName(),
                Boolean.TRUE.equals(method.getIsDefault()),
                ACTIVE,
                method.getCreatedAt(),
                method.getUpdatedAt());
    }
}
