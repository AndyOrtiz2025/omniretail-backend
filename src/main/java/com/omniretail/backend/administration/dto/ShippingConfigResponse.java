package com.omniretail.backend.administration.dto;

public record ShippingConfigResponse(
        ShippingChannelConfigDto ecommerce,
        ShippingChannelConfigDto pos) {
}
