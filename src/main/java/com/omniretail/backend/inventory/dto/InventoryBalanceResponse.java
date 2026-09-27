package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryBalance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryBalanceResponse(
        UUID id,
        UUID tenantId,
        UUID branchId,
        UUID productId,
        UUID locationId,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        Instant createdAt,
        Instant updatedAt) {

    public static InventoryBalanceResponse from(InventoryBalance balance) {
        return new InventoryBalanceResponse(
                balance.getId(),
                balance.getTenantId(),
                balance.getBranchId(),
                balance.getProductId(),
                balance.getLocationId(),
                balance.getQuantity(),
                balance.getReservedQuantity(),
                balance.getCreatedAt(),
                balance.getUpdatedAt());
    }
}
