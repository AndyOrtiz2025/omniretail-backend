package com.omniretail.backend.inventory.dto;

import java.util.UUID;

public record InventorySerialAvailabilityDto(
        UUID serialId,
        String serialNumber,
        UUID lotId,
        UUID branchId,
        UUID locationId) {}
