package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryMovementResponse(
        UUID id,
        UUID tenantId,
        UUID branchId,
        UUID productId,
        InventoryMovementType type,
        String reason,
        BigDecimal quantity,
        BigDecimal quantityBefore,
        BigDecimal quantityAfter,
        String referenceType,
        UUID referenceId,
        UUID performedByUserId,
        Instant createdAt) {

    public static InventoryMovementResponse from(InventoryMovement movement) {
        return new InventoryMovementResponse(
                movement.getId(),
                movement.getTenantId(),
                movement.getBranchId(),
                movement.getProductId(),
                movement.getType(),
                movement.getReason(),
                movement.getQuantity(),
                movement.getQuantityBefore(),
                movement.getQuantityAfter(),
                movement.getReferenceType(),
                movement.getReferenceId(),
                movement.getPerformedByUserId(),
                movement.getCreatedAt());
    }
}
