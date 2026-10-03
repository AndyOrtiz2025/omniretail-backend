package com.omniretail.backend.purchasing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record GoodsReceiptItemResponse(
        UUID id,
        UUID purchaseOrderItemId,
        UUID productId,
        String productNameSnapshot,
        String productSkuSnapshot,
        BigDecimal receivedQuantity,
        UUID unitId,
        String unitSymbolSnapshot,
        BigDecimal purchaseToBaseFactor,
        BigDecimal baseQuantity,
        UUID locationId,
        BigDecimal unitCost,
        List<TrackingDetailRequest> trackingDetails) {}
