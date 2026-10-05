package com.omniretail.backend.inventory.dto;

/**
 * Estado visual de una fila del listado de stock. Los cuatro primeros equivalen a {@link InventoryAlertStatus}
 * (solo productos físicos); los demás cubren filas sin stock propio y no son alertas.
 */
public enum InventoryStockDisplayStatus {
    NORMAL,
    NEAR_MINIMUM,
    CRITICAL,
    OUT_OF_STOCK,
    NOT_CONTROLLED,
    KIT_AVAILABLE,
    KIT_UNAVAILABLE
}
