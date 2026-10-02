package com.omniretail.backend.inventory.dto;

public record InventoryStockSummaryDto(long activeProducts, long lowStock, long outOfStock) {}
