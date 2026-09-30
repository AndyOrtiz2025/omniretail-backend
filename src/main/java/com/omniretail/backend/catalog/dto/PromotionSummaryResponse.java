package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PromotionSummaryResponse(
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
        Instant updatedAt) {

    public static PromotionSummaryResponse from(Promotion promotion, Instant at) {
        return new PromotionSummaryResponse(
                promotion.getId(),
                promotion.getName(),
                promotion.getDiscountType(),
                promotion.getDiscountValue(),
                promotion.getStartsAt(),
                promotion.getEndsAt(),
                lifecycle(promotion, at),
                promotion.getCreatedByUserId(),
                promotion.getCancelledByUserId(),
                promotion.getCancelledAt(),
                promotion.getCreatedAt(),
                promotion.getUpdatedAt());
    }

    public static PromotionLifecycleStatus lifecycle(Promotion promotion, Instant at) {
        if (promotion.getStatus() == com.omniretail.backend.catalog.entity.PromotionStatus.cancelled) {
            return PromotionLifecycleStatus.cancelled;
        }
        if (at.isBefore(promotion.getStartsAt())) {
            return PromotionLifecycleStatus.scheduled;
        }
        if (promotion.getEndsAt() != null && !at.isBefore(promotion.getEndsAt())) {
            return PromotionLifecycleStatus.expired;
        }
        return PromotionLifecycleStatus.active;
    }
}
