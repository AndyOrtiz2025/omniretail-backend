package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.NotBlank;

public record AttributeDefinitionUpdateRequest(@NotBlank String name) {}
