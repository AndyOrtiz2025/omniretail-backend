package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryAlertResponse(
        UUID productId,
        UUID branchId,
        String sku,
        String productName,
        UUID baseUnitId,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal minStock,
        BigDecimal reorderPoint,
        UUID defaultLocationId,
        InventoryAlertStatus status,
        BigDecimal suggestedReorder) {}
