package com.omniretail.backend.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Edicion de una tarjeta guardada (paymentMethodService.updatePaymentMethod): solo titular y
 * vencimiento. Marca, banco, ultimos 4, {@code status} e {@code isDefault} se rechazan con 400 como
 * campos no permitidos; para otra tarjeta se agrega una nueva.
 */
public record UpdatePaymentMethodRequest(
        @Schema(description = "1 a 12.", example = "8") Integer expirationMonth,
        @Schema(description = "Año actual hasta año actual + 20.", example = "2030") Integer expirationYear,
        @Schema(description = "Opcional, máximo 60 caracteres. Vacío lo borra.", example = "Ana López")
                String cardholderName,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
