package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateSaleRequest(
        @NotNull UUID branchId,
        @NotNull UUID cashShiftId,
        UUID customerId,
        @DecimalMin("0.00") BigDecimal taxTotal,
        @NotEmpty @Valid List<Item> items,
        @NotEmpty @Valid List<PaymentLine> payments,
        UUID confirmationId) {

    public CreateSaleRequest(
            UUID branchId,
            UUID cashShiftId,
            UUID customerId,
            BigDecimal taxTotal,
            List<Item> items,
            List<PaymentLine> payments) {
        this(branchId, cashShiftId, customerId, taxTotal, items, payments, null);
    }
    public record Item(@NotNull UUID productId, @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
                       @DecimalMin("0.00") BigDecimal discount) {}
    public record PaymentLine(@NotNull PaymentMethod method, @NotNull @DecimalMin("0.00") BigDecimal amount,
                              @Size(max = 200) String reference) {}
}
