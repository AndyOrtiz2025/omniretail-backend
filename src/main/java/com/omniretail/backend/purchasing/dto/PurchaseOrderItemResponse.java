package com.omniretail.backend.purchasing.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderItemResponse(
        UUID id,
        UUID supplierProductId,
        UUID productId,
        String productName,
        String productSku,
        String supplierSku,
        BigDecimal quantity,
        UUID unitId,
        String unitSymbol,
        BigDecimal purchaseToBaseFactor,
        BigDecimal unitCost,
        BigDecimal suggestedUnitCost,
        BigDecimal subtotal) {}
