package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Componente de un kit en una sucursal. {@code availableQuantity} es la efectiva (0 si el componente ya no es
 * físico + publicado + con control de stock); {@code limiting} marca a todos los que igualan la capacidad del kit.
 */
public record InventoryKitAvailabilityComponentDto(
        UUID componentProductId,
        String sku,
        String productName,
        BigDecimal quantityPerKit,
        BigDecimal availableQuantity,
        long kitCapacity,
        boolean limiting) {}
