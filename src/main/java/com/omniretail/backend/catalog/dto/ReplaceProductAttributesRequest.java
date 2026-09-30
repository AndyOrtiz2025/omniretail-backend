package com.omniretail.backend.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReplaceProductAttributesRequest(
        @NotNull List<@Valid ProductAttributeValueRequest> attributes) {}
