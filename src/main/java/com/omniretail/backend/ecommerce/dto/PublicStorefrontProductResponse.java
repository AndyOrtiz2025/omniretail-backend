package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.catalog.entity.Product;
import java.math.BigDecimal;
import java.util.UUID;

/** Contrato seguro de un producto que puede mostrarse en la tienda pública. */
public record PublicStorefrontProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        String brand,
        BigDecimal salePrice,
        UUID categoryId,
        String categoryName,
        UUID saleUnitId,
        String saleUnitName) {

    public static PublicStorefrontProductResponse from(
            Product product, String categoryName, UUID saleUnitId, String saleUnitName) {
        return new PublicStorefrontProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                product.getSalePrice(),
                product.getCategoryId(),
                categoryName,
                saleUnitId,
                saleUnitName);
    }
}
