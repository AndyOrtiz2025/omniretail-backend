package com.omniretail.backend.administration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record SaveShippingConfigRequest(
        @NotNull @Valid ShippingChannelConfigDto ecommerce,
        @NotNull @Valid ShippingChannelConfigDto pos) {
}
