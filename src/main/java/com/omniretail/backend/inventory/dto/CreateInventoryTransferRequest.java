package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryTransferReason;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateInventoryTransferRequest(
        @NotNull UUID requestingBranchId,
        @NotNull UUID sourceBranchId,
        @NotNull UUID productId,
        @NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal requestedQuantity,
        @NotNull InventoryTransferReason reason,
        @Size(max = 500) String notes) {}
