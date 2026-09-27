package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record InventoryAdjustmentRequest(
        @NotNull UUID branchId,
        @NotNull UUID productId,
        @NotNull InventoryAdjustmentType type,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal quantity,
        @NotBlank @Size(max = 200) String reason,
        @Size(max = 50) String referenceType,
        UUID referenceId) {}
