package com.omniretail.backend.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Datos de una tarjeta nueva, como los envia paymentMethodService.createPaymentMethod. Nunca el numero
 * completo ni el CVV: {@code cardNumber}, {@code cvv}, {@code providerPaymentMethodId}, {@code isDefault},
 * {@code tenantId}, {@code customerId} y cualquier otro campo no declarado se rechazan con 400.
 */
public record CreatePaymentMethodRequest(
        @Schema(description = "Visa, Mastercard, American Express o Discover.", example = "Visa") String brand,
        @Schema(description = "Banco emisor de Guatemala.", example = "Banco Industrial") String issuingBank,
        @Schema(description = "Últimos 4 dígitos.", example = "4242") String last4,
        @Schema(description = "1 a 12.", example = "8") Integer expirationMonth,
        @Schema(description = "Año actual hasta año actual + 20.", example = "2029") Integer expirationYear,
        @Schema(description = "Opcional, máximo 60 caracteres.", example = "Ana López") String cardholderName,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
