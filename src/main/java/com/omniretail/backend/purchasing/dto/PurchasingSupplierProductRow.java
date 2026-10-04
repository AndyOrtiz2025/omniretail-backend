package com.omniretail.backend.purchasing.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Fila de la query paginada de productos de un proveedor (producto y unidad resueltos por join). */
public record PurchasingSupplierProductRow(
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
        Boolean preferred,
        Boolean active) {}
