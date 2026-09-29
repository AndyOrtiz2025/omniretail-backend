package com.omniretail.backend.purchasing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SupplierCostTierRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal minQuantity,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal unitCost) {}
