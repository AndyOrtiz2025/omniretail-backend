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
        @NotNull PromotionDiscountType discountType,
        @NotNull BigDecimal discountValue,
        @NotNull Instant startsAt,
        Instant endsAt,
        @NotEmpty List<@NotNull UUID> productIds) {}
