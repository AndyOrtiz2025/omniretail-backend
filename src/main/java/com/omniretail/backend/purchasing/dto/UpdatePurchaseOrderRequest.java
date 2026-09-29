package com.omniretail.backend.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdatePurchaseOrderRequest(
        @NotNull UUID branchId,
        @NotNull UUID supplierId,
        LocalDate expectedDate,
        @Size(max = 1000) String notes,
        @NotNull List<@Valid PurchaseOrderItemRequest> items) {}
