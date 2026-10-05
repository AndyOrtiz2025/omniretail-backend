package com.omniretail.backend.inventory.dto;

/** Cómo participa un producto en el inventario de una sucursal. */
public enum InventoryProductMode {
    /** Producto físico con stock real y balances propios. */
    TRACKED,
    /** Producto sin control de inventario (servicio). */
    NONE,
    /** Kit sin stock propio: su disponibilidad se deriva de sus componentes físicos. */
    DERIVED_KIT
}
