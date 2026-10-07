package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Consulta de stock de varios productos en una sucursal. Una lista vacía devuelve una respuesta vacía. */
public record InventoryStockBatchRequest(
        @NotNull UUID branchId, @NotNull List<@NotNull UUID> productIds) {}
