package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.LocationType;
import java.time.Instant;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        UUID tenantId,
        UUID branchId,
        UUID parentId,
        String code,
        String name,
        LocationType type,
        LocationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static LocationResponse from(Location location) {
        return new LocationResponse(
                location.getId(),
                location.getTenantId(),
                location.getBranchId(),
                location.getParentId(),
                location.getCode(),
                location.getName(),
                location.getType(),
                location.getStatus(),
                location.getCreatedAt(),
                location.getUpdatedAt());
    }
}
