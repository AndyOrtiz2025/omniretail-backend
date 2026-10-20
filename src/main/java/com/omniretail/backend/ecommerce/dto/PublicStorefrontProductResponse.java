package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Contrato seguro de un producto que puede mostrarse en la tienda pública. */
public record PublicStorefrontProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        String brand,
        String primaryImageUrl,
        String primaryImageAlt,
        BigDecimal salePrice,
        BigDecimal basePrice,
        BigDecimal effectivePrice,
        BigDecimal discountAmount,
        UUID promotionId,
        UUID categoryId,
        String categoryName,
        String categoryImageUrl,
        UUID saleUnitId,
        String saleUnitName,
        boolean inStock,
        BigDecimal availableQuantity,
        List<PublicStorefrontProductAttributeResponse> attributes,
        List<PublicStorefrontProductAvailabilityResponse> availability) {

    public static PublicStorefrontProductResponse from(
            Product product,
            String categoryName,
            String categoryImageUrl,
            UUID saleUnitId,
            String saleUnitName,
            ResolvedProductPrice price,
            String primaryImageUrl,
            String primaryImageAlt,
            boolean inStock,
            BigDecimal availableQuantity) {
        return from(
                product,
                categoryName,
                categoryImageUrl,
                saleUnitId,
                saleUnitName,
                price,
                primaryImageUrl,
                primaryImageAlt,
                inStock,
                availableQuantity,
                List.of(),
                null);
    }

    public static PublicStorefrontProductResponse from(
            Product product,
            String categoryName,
            String categoryImageUrl,
            UUID saleUnitId,
            String saleUnitName,
            ResolvedProductPrice price,
            String primaryImageUrl,
            String primaryImageAlt,
            boolean inStock,
            BigDecimal availableQuantity,
            List<PublicStorefrontProductAttributeResponse> attributes,
            List<PublicStorefrontProductAvailabilityResponse> availability) {
        return new PublicStorefrontProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                primaryImageUrl,
                primaryImageAlt,
                product.getSalePrice(),
                price.basePrice(),
                price.effectivePrice(),
                price.discountAmount(),
                price.promotionId(),
                product.getCategoryId(),
                categoryName,
                categoryImageUrl,
                saleUnitId,
                saleUnitName,
                inStock,
                availableQuantity,
                attributes,
                availability);
    }
}
