package com.omniretail.backend.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreatePurchaseOrderRequest(@NotNull UUID supplierId, @NotNull UUID branchId,
        @NotEmpty @Valid List<Item> items) {
    public record Item(@NotNull UUID productId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitCost) {}
}
