package com.omniretail.backend.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReplaceProductKitComponentsRequest(
        @NotNull List<@NotNull @Valid ProductKitComponentRequest> components) { }
