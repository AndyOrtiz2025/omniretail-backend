package com.omniretail.backend.ecommerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Línea solicitada por el carrito público. El precio se calcula exclusivamente en el servidor. */
public record StorefrontCheckoutItemRequest(
        @NotNull UUID productId,
        @NotNull
                @DecimalMin(value = "1", inclusive = true, message = "La cantidad debe ser un entero mayor a cero")
                @Digits(integer = 9, fraction = 0, message = "La cantidad debe ser un entero mayor a cero")
                BigDecimal quantity) {}
