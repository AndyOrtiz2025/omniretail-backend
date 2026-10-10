package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.catalog.entity.Product;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InventoryInboundCommand(
        UUID tenantId,
        UUID branchId,
        Product product,
        UUID locationId,
        BigDecimal baseQuantity,
        List<InventoryInboundTraceDetail> trackingDetails,
        String reason,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID actorUserId,
        BigDecimal expectedQuantity) {

    public InventoryInboundCommand(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            BigDecimal baseQuantity,
            List<InventoryInboundTraceDetail> trackingDetails,
            String reason,
            String referenceType,
            UUID referenceId,
            UUID referenceLineId,
            UUID actorUserId) {
        this(
                tenantId,
                branchId,
                product,
                locationId,
                baseQuantity,
                trackingDetails,
                reason,
                referenceType,
                referenceId,
                referenceLineId,
                actorUserId,
                null);
    }
}
