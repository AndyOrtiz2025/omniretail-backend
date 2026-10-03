package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InventoryTraceabilitySelection(
        UUID lotId,
        BigDecimal quantity,
        List<String> serialNumbers) {}
