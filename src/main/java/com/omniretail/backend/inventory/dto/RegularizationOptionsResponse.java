package com.omniretail.backend.inventory.dto;

import java.util.List;
import java.util.UUID;

/**
 * Opciones de destino para regularizar un producto en una sucursal. Solo contiene ubicaciones de esa
 * sucursal: la asignada (con su detalle si pertenece a ella) y las activas que podrian asignarse.
 *
 * <p>{@code locationsEnabled = false} indica que el control de ubicaciones esta apagado: no hay
 * regularizacion posible y {@code assignableLocations} va vacio.
 */
public record RegularizationOptionsResponse(
        UUID branchId,
        UUID productId,
        String productName,
        String sku,
        boolean locationsEnabled,
        UUID assignedLocationId,
        LocationOption assignedLocation,
        List<LocationOption> assignableLocations) {

    public record LocationOption(UUID id, String code, String name, String status) {}
}
