package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        UUID tenantId,
        UUID parentId,
        String name,
        String slug,
        String description,
        String imageUrl,
        CategoryStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getTenantId(),
                category.getParentId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getImageUrl(),
                category.getStatus(),
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}
