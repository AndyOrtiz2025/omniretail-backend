package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;

public record InventoryMovementSummaryDto(
        BigDecimal incoming,
        BigDecimal outgoing,
        BigDecimal net) {

    public static InventoryMovementSummaryDto zero() {
        return new InventoryMovementSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
