package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.ProductMedia;
import com.omniretail.backend.catalog.entity.ProductMediaType;
import java.time.Instant;
import java.util.UUID;

public record ProductMediaResponse(UUID id, UUID productId, ProductMediaType type, String url,
        String altText, Boolean primary, Integer sortOrder, Instant createdAt, Instant updatedAt) {
    public static ProductMediaResponse from(ProductMedia value) {
        return new ProductMediaResponse(value.getId(), value.getProductId(), value.getType(), value.getUrl(),
                value.getAltText(), value.getPrimary(), value.getSortOrder(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
