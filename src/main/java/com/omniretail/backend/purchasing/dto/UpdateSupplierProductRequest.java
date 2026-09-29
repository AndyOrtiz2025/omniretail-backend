package com.omniretail.backend.purchasing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record UpdateSupplierProductRequest(
        @Size(max = 100) String supplierSku,
        @NotNull UUID purchaseUnitId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                BigDecimal purchaseToBaseFactor,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal lastCost,
        @NotNull @Min(0) Integer leadTimeDays,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal minimumOrderQuantity) {}
