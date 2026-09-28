package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record UnitConversionCreateRequest(
        UUID productId,
        @NotNull UUID fromUnitId,
        @NotNull UUID toUnitId,
        @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal factor) {}
