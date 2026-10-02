package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryStockItemDto(
        UUID productId,
        UUID branchId,
        String sku,
        String productName,
        UUID categoryId,
        String categoryName,
        UUID baseUnitId,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal minStock,
        BigDecimal reorderPoint,
        UUID defaultLocationId,
        String defaultLocationName,
        InventoryAlertStatus status,
        BigDecimal suggestedReorder) {}
