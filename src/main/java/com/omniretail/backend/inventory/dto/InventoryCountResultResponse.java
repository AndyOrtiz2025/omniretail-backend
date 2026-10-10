package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Evidencia canonica de un conteo confirmado. */
public record InventoryCountResultResponse(
        UUID countId,
        Instant createdAt,
        UUID productId,
        String productName,
        String sku,
        UUID branchId,
        String branchName,
        UUID locationId,
        String locationName,
        UUID performedByUserId,
        String performedByName,
        BigDecimal quantityBefore,
        BigDecimal countedQuantity,
        BigDecimal quantityAfter,
        BigDecimal delta,
        List<LotResult> lots,
        List<UUID> movementIds) {

    public record LotResult(
            UUID lotId,
            String lotNumber,
            LocalDate expirationDate,
            BigDecimal quantityBefore,
            BigDecimal countedQuantity,
            BigDecimal delta,
            List<String> foundSerialNumbers,
            List<String> missingSerialNumbers,
            List<String> addedSerialNumbers) {}
}
