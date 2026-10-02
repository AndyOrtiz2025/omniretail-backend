package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductKitComponent;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductKitComponentResponse(UUID id, UUID componentProductId, String sku,
        String name, BigDecimal quantityPerKit) {
    public static ProductKitComponentResponse from(ProductKitComponent value, Product product) {
        return new ProductKitComponentResponse(value.getId(), value.getComponentProductId(),
                product.getSku(), product.getName(), value.getQuantityPerKit());
    }
}
