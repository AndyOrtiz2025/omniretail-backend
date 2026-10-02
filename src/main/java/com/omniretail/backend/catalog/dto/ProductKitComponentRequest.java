package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductKitComponentRequest(@NotNull UUID componentProductId,
        @NotNull @Positive @DecimalMax("999999999.999") @Digits(integer = 9, fraction = 3)
        BigDecimal quantityPerKit) { }
