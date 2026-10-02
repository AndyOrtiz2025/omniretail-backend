package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePromotionRequest(
        @NotBlank String name,
        String description,
        @NotNull PromotionDiscountType discountType,
        @NotNull BigDecimal discountValue,
        @NotNull Instant startsAt,
        Instant endsAt,
        @NotEmpty List<@NotNull UUID> productIds,
        @NotEmpty List<@NotBlank String> channels,
        Boolean untilStockEnds,
        @NotNull List<@NotNull UUID> branchIds) {
    public CreatePromotionRequest(String name, PromotionDiscountType discountType, BigDecimal discountValue,
            Instant startsAt, Instant endsAt, List<UUID> productIds) {
        this(name, null, discountType, discountValue, startsAt, endsAt, productIds,
                List.of("pos", "ecommerce", "mobileApp"), false, List.of());
    }
}
