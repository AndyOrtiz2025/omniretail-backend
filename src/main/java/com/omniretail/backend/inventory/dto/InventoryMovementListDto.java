package com.omniretail.backend.inventory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryMovementListDto(
        UUID id,
        UUID tenantId,
        UUID branchId,
        String branchName,
        UUID productId,
        String productName,
        String sku,
        InventoryMovementType type,
        @JsonProperty("displayType") String displayType,
        String reason,
        BigDecimal quantity,
        BigDecimal quantityBefore,
        BigDecimal quantityAfter,
        UUID fromLocationId,
        String fromLocationName,
        UUID toLocationId,
        String toLocationName,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        String referenceLabel,
        UUID performedByUserId,
        String userLabel,
        Instant createdAt) {}
