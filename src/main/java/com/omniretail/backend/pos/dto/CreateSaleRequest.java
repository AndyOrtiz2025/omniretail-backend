package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.SaleDocumentType;
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
        @NotNull UUID confirmationId,
        @Valid Document document) {

    public CreateSaleRequest(
            UUID branchId,
            UUID cashShiftId,
            UUID customerId,
            BigDecimal taxTotal,
            List<Item> items,
            List<PaymentLine> payments,
            UUID confirmationId) {
        this(branchId, cashShiftId, customerId, taxTotal, items, payments, confirmationId, null);
    }

    public CreateSaleRequest(
            UUID branchId,
            UUID cashShiftId,
            UUID customerId,
            BigDecimal taxTotal,
            List<Item> items,
            List<PaymentLine> payments) {
        this(branchId, cashShiftId, customerId, taxTotal, items, payments, UUID.randomUUID(), null);
    }

    public record Document(
            @NotNull SaleDocumentType type,
            @Size(max = 100) String taxId,
            @Size(max = 300) String legalName,
            @Size(max = 500) String fiscalAddress) {}

    public record Item(@NotNull UUID productId, @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
                       @DecimalMin("0.00") BigDecimal discount) {}
    public record PaymentLine(
            @NotNull PaymentMethod method,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            UUID bankAccountId,
            @Size(max = 200) String reference,
            Boolean externallyVerified) {

        public PaymentLine(PaymentMethod method, BigDecimal amount, String reference) {
            this(method, amount, null, reference, null);
        }
    }
}
