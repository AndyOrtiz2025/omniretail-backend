package com.omniretail.backend.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReceiveInventoryTransferItemRequest(
        @NotNull UUID itemId,
        @NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal receivedQuantity,
        List<@Valid InventoryTransferTrackingSelectionRequest> receivedSelections) {

    public ReceiveInventoryTransferItemRequest(UUID itemId, BigDecimal receivedQuantity) {
        this(itemId, receivedQuantity, List.of());
    }
}
