package com.omniretail.backend.pos.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record VoidSaleResponse(
        UUID operationId,
        boolean idempotent,
        String reason,
        SaleResponse sale,
        InventoryEffect inventory,
        CashMovementEffect cashMovement) {

    public record InventoryEffect(
            boolean inventoryRestored,
            List<UUID> movementIds,
            int reservationsReleased) {}

    public record CashMovementEffect(
            boolean recorded,
            List<UUID> movementIds,
            BigDecimal amount) {}

    public VoidSaleResponse asReplay() {
        return new VoidSaleResponse(
                operationId, true, reason, sale, inventory, cashMovement);
    }
}
