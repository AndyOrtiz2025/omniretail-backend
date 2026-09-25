package com.omniretail.backend.ecommerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Línea solicitada por el carrito público. El precio se calcula exclusivamente en el servidor. */
public record StorefrontCheckoutItemRequest(
        @NotNull UUID productId,
        @NotNull @DecimalMin(value = "0.000", inclusive = false) BigDecimal quantity) {}
