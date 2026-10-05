package com.omniretail.backend.inventory.dto;

import java.util.List;
import java.util.UUID;

/** Explica la disponibilidad DERIVADA de un kit: cuántos kits completos permiten sus componentes. */
public record InventoryKitAvailabilityResponse(
        UUID kitProductId,
        UUID branchId,
        long availableKits,
        List<InventoryKitAvailabilityComponentDto> components) {}
