package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.ReceiptIncident;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReceiptIncidentResponse(
        UUID id,
        UUID branchId,
        UUID goodsReceiptId,
        UUID goodsReceiptItemId,
        ReceiptIncidentType incidentType,
        ReceiptIncidentStatus status,
        BigDecimal quantityAffected,
        String notes,
        UUID createdByUserId,
        UUID resolvedByUserId,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ReceiptIncidentResponse from(ReceiptIncident incident) {
        return new ReceiptIncidentResponse(
                incident.getId(),
                incident.getBranchId(),
                incident.getGoodsReceiptId(),
                incident.getGoodsReceiptItemId(),
                incident.getIncidentType(),
                incident.getStatus(),
                incident.getQuantityAffected(),
                incident.getNotes(),
                incident.getCreatedByUserId(),
                incident.getResolvedByUserId(),
                incident.getResolvedAt(),
                incident.getCreatedAt(),
                incident.getUpdatedAt());
    }
}
