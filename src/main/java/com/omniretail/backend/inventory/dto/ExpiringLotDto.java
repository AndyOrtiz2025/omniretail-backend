package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ExpiringLotDto(
        UUID lotId,
        String lotNumber,
        LocalDate expirationDate,
        long daysUntilExpiration,
        UUID productId,
        String sku,
        String productName,
        UUID branchId,
        UUID locationId,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity) {}
