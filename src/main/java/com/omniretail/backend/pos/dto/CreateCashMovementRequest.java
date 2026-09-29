package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.CashMovementType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateCashMovementRequest(
        @NotNull UUID cashShiftId,
        @NotNull CashMovementType type,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {}
