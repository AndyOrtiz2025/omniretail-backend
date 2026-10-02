package com.omniretail.backend.inventory.dto;

import java.util.List;

public record InventoryMovementPageResponse(
        List<InventoryMovementListDto> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages,
        InventoryMovementSummaryDto summary) {}
