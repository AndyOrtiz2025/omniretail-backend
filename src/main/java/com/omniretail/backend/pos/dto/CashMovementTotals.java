package com.omniretail.backend.pos.dto;

import java.math.BigDecimal;

public record CashMovementTotals(
        BigDecimal cashIn,
        BigDecimal cashOut,
        BigDecimal manualCashIn,
        BigDecimal manualCashOut,
        BigDecimal salesCashIn,
        BigDecimal voidCashOut,
        BigDecimal returnCashOut) {}
