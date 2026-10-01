package com.omniretail.backend.logistics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SavePackingPreparationRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 128) String operationId,
        @NotNull @Valid PackingChecklistRequest checklist,
        @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal totalWeight,
        @Min(1) Integer packageCount) {}
