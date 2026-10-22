package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.ShippingConfig;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Tarifa de envío de un canal. {@code enabled = false} no cobra envío; {@code freeShippingThreshold}
 * nulo significa que nunca es gratis.
 */
public record ShippingChannelConfigDto(
        boolean enabled,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal deliveryFee,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal freeShippingThreshold) {

    public static ShippingChannelConfigDto from(ShippingConfig config) {
        return new ShippingChannelConfigDto(
                config.isEnabled(), config.getDeliveryFee(), config.getFreeShippingThreshold());
    }
}
