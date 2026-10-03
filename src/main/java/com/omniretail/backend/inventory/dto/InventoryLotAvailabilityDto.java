package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InventoryLotAvailabilityDto(
        UUID lotId,
        String lotNumber,
        LocalDate expirationDate,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        UUID locationId) {}
