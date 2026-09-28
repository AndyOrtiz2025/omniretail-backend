package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitCategory;
import com.omniretail.backend.catalog.entity.UnitStatus;
import java.time.Instant;
import java.util.UUID;

public record UnitResponse(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        String symbol,
        UnitCategory category,
        Boolean allowsDecimals,
        UnitStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static UnitResponse from(Unit unit) {
        return new UnitResponse(
                unit.getId(),
                unit.getTenantId(),
                unit.getCode(),
                unit.getName(),
                unit.getSymbol(),
                unit.getCategory(),
                unit.getAllowsDecimals(),
                unit.getStatus(),
                unit.getCreatedAt(),
                unit.getUpdatedAt());
    }
}
