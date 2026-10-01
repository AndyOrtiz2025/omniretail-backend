package com.omniretail.backend.logistics.dto;

import java.math.BigDecimal;

public record PickingProgressResponse(
        BigDecimal requiredQuantity,
        BigDecimal pickedQuantity,
        BigDecimal remainingQuantity,
        int percentage) {}
