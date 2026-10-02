package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record PromotionSummaryResponse(
        UUID id,
        String name,
        String description,
        PromotionDiscountType discountType,
        BigDecimal discountValue,
        Instant startsAt,
        Instant endsAt,
        PromotionLifecycleStatus status,
        List<String> channels,
        Boolean untilStockEnds,
        List<UUID> branchIds,
        UUID createdByUserId,
        UUID cancelledByUserId,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt) {

    public static PromotionSummaryResponse from(Promotion promotion, Instant at) {
        return new PromotionSummaryResponse(
                promotion.getId(),
                promotion.getName(),
                promotion.getDescription(),
                promotion.getDiscountType(),
                promotion.getDiscountValue(),
                promotion.getStartsAt(),
                promotion.getEndsAt(),
                lifecycle(promotion, at),
                promotion.getChannels() == null ? List.of() : promotion.getChannels(),
                Boolean.TRUE.equals(promotion.getUntilStockEnds()),
                promotion.getBranchIds() == null ? List.of() : promotion.getBranchIds(),
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
        if (promotion.getStatus() == com.omniretail.backend.catalog.entity.PromotionStatus.ended) {
            return PromotionLifecycleStatus.ended;
        }
        if (at.isBefore(promotion.getStartsAt())) {
            return PromotionLifecycleStatus.scheduled;
        }
        if (promotion.getEndsAt() != null && !at.isBefore(promotion.getEndsAt())) {
            return PromotionLifecycleStatus.ended;
        }
        return PromotionLifecycleStatus.active;
    }
}
