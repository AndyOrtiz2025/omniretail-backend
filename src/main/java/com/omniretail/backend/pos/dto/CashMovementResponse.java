package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashMovementResponse(UUID id, UUID cashShiftId, CashMovementType type, BigDecimal amount,
                                   String reason, String referenceType, UUID referenceId,
                                   UUID createdByUserId, Instant createdAt, String saleNumber) {
    public static CashMovementResponse from(CashMovement movement) {
        return from(movement, null);
    }

    public static CashMovementResponse from(CashMovement movement, String saleNumber) {
        return new CashMovementResponse(movement.getId(), movement.getCashShiftId(), movement.getType(),
                movement.getAmount(), movement.getReason(), movement.getReferenceType(), movement.getReferenceId(),
                movement.getCreatedByUserId(), movement.getCreatedAt(), saleNumber);
    }
}
