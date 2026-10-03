package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Seleccion fisica canonica persistida en Picking y reutilizada por despacho/recepcion. */
public record InventoryPhysicalSelection(
        UUID locationId,
        UUID lotId,
        BigDecimal quantity,
        List<String> serialNumbers) {}
