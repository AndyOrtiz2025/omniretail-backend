package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stock de un producto físico en una sucursal, en unidad base. Mismos nombres y reglas de estado/reorden que
 * {@link InventoryStockItemDto}, sin metadata de catálogo.
 */
public record InventoryStockBatchItemDto(
        UUID productId,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal minStock,
        BigDecimal reorderPoint,
        InventoryAlertStatus status,
        BigDecimal suggestedReorder) {}
