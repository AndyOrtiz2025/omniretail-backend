package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import java.math.BigDecimal;
import java.util.UUID;

public record InventoryTransferItemResponse(
        UUID id,
        UUID productId,
        UUID sourceRequestId,
        BigDecimal requestedQuantity,
        BigDecimal dispatchedQuantity,
        BigDecimal receivedQuantity) {

    public static InventoryTransferItemResponse from(InventoryTransferItem item) {
        return new InventoryTransferItemResponse(
                item.getId(),
                item.getProductId(),
                item.getSourceRequestId(),
                item.getRequestedQuantity(),
                item.getDispatchedQuantity(),
                item.getReceivedQuantity());
    }
}
