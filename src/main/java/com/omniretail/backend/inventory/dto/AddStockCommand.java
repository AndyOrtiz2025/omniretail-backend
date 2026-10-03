package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AddStockCommand(
        UUID tenantId,
        UUID branchId,
        UUID productId,
        BigDecimal qty,
        String reason,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID performedByUserId) {

    public AddStockCommand(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            BigDecimal qty,
            String reason,
            String referenceType,
            UUID referenceId,
            UUID performedByUserId) {
        this(tenantId, branchId, productId, qty, reason, referenceType, referenceId, null,
                performedByUserId);
    }
}
