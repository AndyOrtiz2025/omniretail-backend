package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.SaleStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaleReturnOperationResponse(
        UUID operationId,
        boolean idempotent,
        String reason,
        SaleStatus saleStatus,
        SaleReturnResponse saleReturn,
        BigDecimal commercialRefundAmount,
        InventoryEffect inventory,
        CashMovementEffect cashMovement) {

    public record InventoryEffect(
            boolean inventoryRestored,
            List<UUID> movementIds) {}

    public record CashMovementEffect(
            boolean recorded,
            List<UUID> movementIds,
            BigDecimal amount) {}

    public SaleReturnOperationResponse asReplay() {
        return new SaleReturnOperationResponse(
                operationId,
                true,
                reason,
                saleStatus,
                saleReturn,
                commercialRefundAmount,
                inventory,
                cashMovement);
    }
}
