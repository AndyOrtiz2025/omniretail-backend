package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.logistics.entity.PickingIncident;
import com.omniretail.backend.logistics.entity.PickingIncidentStatus;
import com.omniretail.backend.logistics.entity.PickingIncidentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PickingIncidentResponse(
        UUID id,
        UUID pickingOrderId,
        UUID pickingLineId,
        PickingIncidentType type,
        BigDecimal quantityAffected,
        String comment,
        PickingIncidentStatus status,
        UUID createdBy,
        Instant createdAt,
        UUID resolvedBy,
        Instant resolvedAt) {

    public static PickingIncidentResponse from(PickingIncident incident) {
        return new PickingIncidentResponse(
                incident.getId(),
                incident.getPickingOrderId(),
                incident.getPickingItemId(),
                incident.getIncidentType(),
                incident.getQuantityAffected(),
                incident.getComment(),
                incident.getStatus(),
                incident.getCreatedByUserId(),
                incident.getCreatedAt(),
                incident.getResolvedByUserId(),
                incident.getResolvedAt());
    }
}
