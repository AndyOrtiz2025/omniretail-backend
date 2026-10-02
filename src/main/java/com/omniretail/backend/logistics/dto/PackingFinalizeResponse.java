package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;

public record PackingFinalizeResponse(
        PackingDetailResponse packing,
        boolean idempotent,
        OrderStatus orderStatus,
        InventoryTransferStatus transferStatus) {

    public PackingFinalizeResponse(
            PackingDetailResponse packing, boolean idempotent, OrderStatus orderStatus) {
        this(packing, idempotent, orderStatus, null);
    }
}
