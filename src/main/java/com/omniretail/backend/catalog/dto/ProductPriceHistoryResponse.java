package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.ProductPriceHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductPriceHistoryResponse(
        UUID id,
        UUID productId,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        UUID changedByUserId,
        String reason,
        Instant createdAt) {

    public static ProductPriceHistoryResponse from(ProductPriceHistory history) {
        return new ProductPriceHistoryResponse(
                history.getId(),
                history.getProductId(),
                history.getOldPrice(),
                history.getNewPrice(),
                history.getChangedByUserId(),
                history.getReason(),
                history.getCreatedAt());
    }
}
