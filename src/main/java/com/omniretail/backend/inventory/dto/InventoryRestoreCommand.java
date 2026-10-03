package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.catalog.entity.Product;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InventoryRestoreCommand(
        UUID tenantId,
        UUID branchId,
        Product product,
        UUID locationId,
        BigDecimal baseQuantity,
        List<InventoryTraceabilitySelection> selections,
        String reason,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID actorUserId) {}
