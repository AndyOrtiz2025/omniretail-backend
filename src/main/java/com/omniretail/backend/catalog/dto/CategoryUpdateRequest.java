package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.CategoryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CategoryUpdateRequest(
        UUID parentId,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String slug,
        String description,
        String imageUrl,
        @NotNull CategoryStatus status) {}
