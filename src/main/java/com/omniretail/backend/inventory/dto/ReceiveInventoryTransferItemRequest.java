package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ReceiveInventoryTransferItemRequest(
        @NotNull UUID itemId,
        @NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal receivedQuantity) {}
