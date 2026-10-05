package com.omniretail.backend.inventory.dto;

import com.omniretail.backend.catalog.entity.ProductType;
import java.math.BigDecimal;
import java.time.LocalDate;
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
        LocalDate nextExpirationDate,
        InventoryAlertStatus status,
        BigDecimal suggestedReorder,
        ProductType productType,
        InventoryProductMode inventoryMode,
        InventoryStockDisplayStatus displayStatus) {}
