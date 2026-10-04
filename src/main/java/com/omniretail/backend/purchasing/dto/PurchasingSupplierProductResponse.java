package com.omniretail.backend.purchasing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Producto de un proveedor en el detalle informativo de Compras. */
public record PurchasingSupplierProductResponse(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        String supplierSku,
        String purchaseUnitSymbol,
        BigDecimal purchaseToBaseFactor,
        BigDecimal lastCost,
        Integer leadTimeDays,
        BigDecimal minimumOrderQuantity,
        boolean preferred,
        boolean active,
        List<CostTier> costTiers) {

    public record CostTier(BigDecimal minQuantity, BigDecimal unitCost) {}

    public static PurchasingSupplierProductResponse from(
            PurchasingSupplierProductRow row, List<CostTier> costTiers) {
        return new PurchasingSupplierProductResponse(
                row.id(),
                row.productId(),
                row.productName(),
                row.productSku(),
                row.supplierSku(),
                row.purchaseUnitSymbol(),
                row.purchaseToBaseFactor(),
                row.lastCost(),
                row.leadTimeDays(),
                row.minimumOrderQuantity(),
                Boolean.TRUE.equals(row.preferred()),
                Boolean.TRUE.equals(row.active()),
                costTiers);
    }
}
