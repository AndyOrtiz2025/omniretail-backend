package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.CashShiftStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashShiftSummaryResponse(
        UUID cashShiftId,
        UUID branchId,
        UUID cashierId,
        String registerCode,
        CashShiftStatus status,
        Instant openedAt,
        Instant closedAt,
        BigDecimal openingAmount,
        BigDecimal cashIn,
        BigDecimal cashOut,
        BigDecimal manualCashIn,
        BigDecimal manualCashOut,
        BigDecimal salesCashIn,
        BigDecimal voidCashOut,
        BigDecimal returnCashOut,
        BigDecimal expectedAmount,
        BigDecimal countedAmount,
        BigDecimal difference) {}
