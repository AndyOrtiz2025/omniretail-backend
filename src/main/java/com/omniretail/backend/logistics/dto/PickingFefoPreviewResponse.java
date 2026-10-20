package com.omniretail.backend.logistics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PickingFefoPreviewResponse(
        UUID pickingOrderId,
        UUID pickingLineId,
        UUID productId,
        UUID branchId,
        Location location,
        BigDecimal currentPickedQuantity,
        BigDecimal targetPickedQuantity,
        BigDecimal incrementalQuantity,
        List<Allocation> existingSelections,
        List<Allocation> incrementalAllocations,
        List<Allocation> resultingSelections,
        Instant calculatedAt,
        boolean provisional) {

    public record Location(UUID id, String code, String name) {}

    public record Allocation(
            UUID locationId,
            String locationCode,
            String locationName,
            UUID lotId,
            String lotNumber,
            LocalDate expirationDate,
            BigDecimal quantity) {}
}
