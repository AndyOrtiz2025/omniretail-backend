package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductDto(
        UUID id,
        UUID tenantId,
        String sku,
        String barcode,
        String name,
        String description,
        String brand,
        ProductType productType,
        UUID categoryId,
        UUID baseUnitId,
        UUID inventoryUnitId,
        UUID saleUnitId,
        BigDecimal salePrice,
        ProductStatus status,
        ProductTrackingDto tracking,
        ProductChannelsDto channels,
        Instant createdAt,
        Instant updatedAt) {
}
