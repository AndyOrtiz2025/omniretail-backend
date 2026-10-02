package com.omniretail.backend.inventory.dto;

import java.util.List;

public record InventoryStockPageResponse(
        List<InventoryStockItemDto> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages,
        InventoryStockSummaryDto summary) {}
