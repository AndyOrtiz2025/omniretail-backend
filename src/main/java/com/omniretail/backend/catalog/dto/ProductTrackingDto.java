package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.NotNull;

public record ProductTrackingDto(
        @NotNull Boolean stock,
        @NotNull Boolean lot,
        @NotNull Boolean expiration,
        @NotNull Boolean serial) {
}
