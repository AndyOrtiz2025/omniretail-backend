package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.AttributeDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AttributeDefinitionCreateRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull AttributeDataType dataType) {}
