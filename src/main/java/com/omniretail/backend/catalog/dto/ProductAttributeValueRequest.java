package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ProductAttributeValueRequest(
        @NotNull UUID attributeId,
        @NotNull String value) {}
