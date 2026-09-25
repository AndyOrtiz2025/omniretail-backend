package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.NotNull;

public record ProductChannelsDto(
        @NotNull Boolean ecommerce,
        @NotNull Boolean pos,
        @NotNull Boolean mobileApp) {
}
