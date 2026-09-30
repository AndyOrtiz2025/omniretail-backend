package com.omniretail.backend.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ResolvedProductPrice(
        BigDecimal basePrice,
        BigDecimal effectivePrice,
        BigDecimal discountAmount,
        UUID promotionId) {}
