package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.UnitCategory;
import com.omniretail.backend.catalog.entity.UnitStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UnitUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 10) String symbol,
        @NotNull UnitCategory category,
        @NotNull Boolean allowsDecimals,
        @NotNull UnitStatus status) {}
