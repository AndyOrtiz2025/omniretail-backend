package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.SupplierProduct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SupplierProductResponse(
        UUID id,
        UUID tenantId,
        UUID supplierId,
        UUID productId,
        String supplierSku,
        UUID purchaseUnitId,
        BigDecimal purchaseToBaseFactor,
        BigDecimal lastCost,
        Integer leadTimeDays,
        BigDecimal minimumOrderQuantity,
        boolean preferred,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        List<SupplierCostTierResponse> costTiers) {

    public static SupplierProductResponse from(
            SupplierProduct supplierProduct, List<SupplierCostTierResponse> costTiers) {
        return new SupplierProductResponse(
                supplierProduct.getId(),
                supplierProduct.getTenantId(),
                supplierProduct.getSupplierId(),
                supplierProduct.getProductId(),
                supplierProduct.getSupplierSku(),
                supplierProduct.getPurchaseUnitId(),
                supplierProduct.getPurchaseToBaseFactor(),
                supplierProduct.getLastCost(),
                supplierProduct.getLeadTimeDays(),
                supplierProduct.getMinimumOrderQuantity(),
                supplierProduct.getPreferred(),
                supplierProduct.getActive(),
                supplierProduct.getCreatedAt(),
                supplierProduct.getUpdatedAt(),
                List.copyOf(costTiers));
    }
}
