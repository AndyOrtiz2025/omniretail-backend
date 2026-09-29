package com.omniretail.backend.pos.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateSaleReturnRequest(
        @NotBlank @Size(max = 1000) String reason,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotNull UUID saleItemId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal quantity) {}
}
