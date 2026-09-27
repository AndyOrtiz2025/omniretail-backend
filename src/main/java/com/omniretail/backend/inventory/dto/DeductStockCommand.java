package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DeductStockCommand(
        UUID tenantId,
        UUID branchId,
        UUID productId,
        BigDecimal qty,
        String reason,
        String referenceType,
        UUID referenceId,
        UUID performedByUserId) {}
