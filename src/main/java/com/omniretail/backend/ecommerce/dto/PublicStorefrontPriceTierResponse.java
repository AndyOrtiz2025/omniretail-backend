package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.catalog.dto.ResolvedTierPrice;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Precio desde `minQuantity` unidades. `effectivePrice` ya incluye la promoción aplicable y es el
 * precio unitario que cobra el checkout para esa cantidad.
 */
public record PublicStorefrontPriceTierResponse(
        int minQuantity,
        BigDecimal unitPrice,
        BigDecimal effectivePrice,
        BigDecimal discountAmount,
        UUID promotionId) {

    public static PublicStorefrontPriceTierResponse from(ResolvedTierPrice tier) {
        return new PublicStorefrontPriceTierResponse(
                tier.minQuantity(),
                tier.price().basePrice(),
                tier.price().effectivePrice(),
                tier.price().discountAmount(),
                tier.price().promotionId());
    }
}
