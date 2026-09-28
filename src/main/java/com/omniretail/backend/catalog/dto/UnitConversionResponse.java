package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.UnitConversion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UnitConversionResponse(
        UUID id,
        UUID tenantId,
        UUID productId,
        UUID fromUnitId,
        UUID toUnitId,
        BigDecimal factor,
        Instant createdAt) {

    public static UnitConversionResponse from(UnitConversion entity) {
        return new UnitConversionResponse(
                entity.getId(),
                entity.getTenantId(),
                entity.getProductId(),
                entity.getFromUnitId(),
                entity.getToUnitId(),
                entity.getFactor(),
                entity.getCreatedAt());
    }
}
