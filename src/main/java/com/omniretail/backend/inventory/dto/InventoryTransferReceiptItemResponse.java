package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryTransferReceiptItem;
import java.math.BigDecimal;
import java.util.UUID;

public record InventoryTransferReceiptItemResponse(
        UUID id,
        UUID transferItemId,
        UUID productId,
        UUID locationId,
        BigDecimal receivedQuantity) {

    public static InventoryTransferReceiptItemResponse from(
            InventoryTransferReceiptItem item) {
        return new InventoryTransferReceiptItemResponse(
                item.getId(),
                item.getTransferItemId(),
                item.getProductId(),
                item.getLocationId(),
                item.getQuantity());
    }
}
