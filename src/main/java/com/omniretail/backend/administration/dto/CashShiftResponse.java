package com.omniretail.backend.administration.dto;

import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashShiftResponse(
        UUID id,
        UUID branchId,
        UUID userId,
        String registerCode,
        CashShiftStatus status,
        Instant openedAt,
        BigDecimal openingAmount,
        Instant closedAt,
        BigDecimal expectedAmount,
        BigDecimal countedAmount,
        BigDecimal difference,
        Instant createdAt,
        Instant updatedAt) {

    public static CashShiftResponse from(CashShift shift) {
        return new CashShiftResponse(
                shift.getId(),
                shift.getBranchId(),
                shift.getUserId(),
                shift.getRegisterCode(),
                shift.getStatus(),
                shift.getOpenedAt(),
                shift.getOpeningAmount(),
                shift.getClosedAt(),
                shift.getExpectedAmount(),
                shift.getCountedAmount(),
                shift.getDifference(),
                shift.getCreatedAt(),
                shift.getUpdatedAt());
    }
}
