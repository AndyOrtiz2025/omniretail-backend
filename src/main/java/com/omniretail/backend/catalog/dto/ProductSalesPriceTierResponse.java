package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.ProductSalesPriceTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductSalesPriceTierResponse(UUID id, UUID productId, Integer minQuantity,
        BigDecimal unitPrice, Boolean active, Instant createdAt, Instant updatedAt) {
    public static ProductSalesPriceTierResponse from(ProductSalesPriceTier value) {
        return new ProductSalesPriceTierResponse(value.getId(), value.getProductId(), value.getMinQuantity(),
                value.getUnitPrice(), value.getActive(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
