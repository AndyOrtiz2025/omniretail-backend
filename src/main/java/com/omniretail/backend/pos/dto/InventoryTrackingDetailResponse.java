package com.omniretail.backend.pos.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InventoryTrackingDetailResponse(
        UUID productId,
        UUID locationId,
        UUID lotId,
        String lotNumber,
        BigDecimal quantity,
        List<String> serialNumbers) {}
