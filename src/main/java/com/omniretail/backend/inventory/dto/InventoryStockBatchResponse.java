package com.omniretail.backend.inventory.dto;

import java.util.List;
import java.util.UUID;

/** Una fila por producto solicitado (sin duplicados), en el orden de la solicitud. */
public record InventoryStockBatchResponse(UUID branchId, List<InventoryStockBatchItemDto> items) {}
