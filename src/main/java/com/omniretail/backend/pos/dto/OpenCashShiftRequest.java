package com.omniretail.backend.pos.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record OpenCashShiftRequest(
        @NotNull UUID branchId,
        @NotBlank @Size(max = 50) String registerCode,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal openingAmount) {}
