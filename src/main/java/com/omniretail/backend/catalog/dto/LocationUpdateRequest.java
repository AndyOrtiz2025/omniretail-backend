package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.LocationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LocationUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull LocationStatus status) {}
