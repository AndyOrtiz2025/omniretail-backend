package com.omniretail.backend.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateGoodsReceiptRequest(
        @Size(max = 1000) String notes,
        @NotNull @Size(min = 1) List<@Valid GoodsReceiptItemRequest> items) {}
