package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductInventorySettingsResponse(
        UUID id,
        UUID branchId,
        UUID productId,
        BigDecimal minStock,
        BigDecimal reorderPoint,
        UUID defaultLocationId,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductInventorySettingsResponse from(ProductInventorySettings settings) {
        return new ProductInventorySettingsResponse(
                settings.getId(),
                settings.getBranchId(),
                settings.getProductId(),
                settings.getMinStock(),
                settings.getReorderPoint(),
                settings.getDefaultLocationId(),
                settings.getCreatedAt(),
                settings.getUpdatedAt());
    }
}
