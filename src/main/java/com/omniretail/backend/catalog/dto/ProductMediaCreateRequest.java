package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.ProductMediaType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductMediaCreateRequest(@NotNull ProductMediaType type, @NotBlank String url,
        String altText, @Min(0) Integer sortOrder, Boolean primary) { }
