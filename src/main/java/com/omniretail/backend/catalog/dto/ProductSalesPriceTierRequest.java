package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductSalesPriceTierRequest(
        @NotNull @Min(2) @Max(999999) Integer minQuantity,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @DecimalMax("9999999.99")
        @Digits(integer = 7, fraction = 2) BigDecimal unitPrice,
        Boolean active) { }
