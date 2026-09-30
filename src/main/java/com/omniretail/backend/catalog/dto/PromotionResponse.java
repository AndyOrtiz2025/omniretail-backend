package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PromotionResponse(
        UUID id,
        String name,
        PromotionDiscountType discountType,
        BigDecimal discountValue,
        Instant startsAt,
        Instant endsAt,
        PromotionLifecycleStatus status,
        UUID createdByUserId,
        UUID cancelledByUserId,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt,
        List<PromotionProductResponse> products) {

    public static PromotionResponse from(
            Promotion promotion, Instant at, List<PromotionProductResponse> products) {
        PromotionSummaryResponse summary = PromotionSummaryResponse.from(promotion, at);
        return new PromotionResponse(
                summary.id(), summary.name(), summary.discountType(), summary.discountValue(),
                summary.startsAt(), summary.endsAt(), summary.status(), summary.createdByUserId(),
                summary.cancelledByUserId(), summary.cancelledAt(), summary.createdAt(),
                summary.updatedAt(), products);
    }
}
