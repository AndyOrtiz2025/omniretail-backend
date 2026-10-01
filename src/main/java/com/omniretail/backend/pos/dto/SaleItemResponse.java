package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.SaleItem;
import java.math.BigDecimal;
import java.util.UUID;

public record SaleItemResponse(UUID id, UUID productId, UUID promotionId, String sku, String name,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal subtotal) {
    public static SaleItemResponse from(SaleItem item) {
        return new SaleItemResponse(item.getId(), item.getProductId(), item.getPromotionId(), item.getSkuSnapshot(),
                item.getNameSnapshot(), item.getQuantity(), item.getUnitPrice(), item.getDiscount(), item.getSubtotal());
    }
}
