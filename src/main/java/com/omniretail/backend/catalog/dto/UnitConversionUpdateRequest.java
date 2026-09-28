package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record UnitConversionUpdateRequest(
        @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal factor) {}
