package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InventoryHistoricalTraceDetail(
        UUID productId,
        UUID locationId,
        UUID lotId,
        String lotNumber,
        BigDecimal quantity,
        List<String> serialNumbers) {}
