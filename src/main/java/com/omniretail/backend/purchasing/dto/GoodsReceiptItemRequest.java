package com.omniretail.backend.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record GoodsReceiptItemRequest(
        @NotNull UUID purchaseOrderItemId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
                BigDecimal receivedQuantity,
        UUID locationId,
        List<@Valid TrackingDetailRequest> trackingDetails) {

    public GoodsReceiptItemRequest(
            UUID purchaseOrderItemId, BigDecimal receivedQuantity, UUID locationId) {
        this(purchaseOrderItemId, receivedQuantity, locationId, List.of());
    }
}
