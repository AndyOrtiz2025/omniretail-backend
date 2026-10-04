package com.omniretail.backend.pos.dto;

import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.SaleDocumentType;
import com.omniretail.backend.shared.validation.GuatemalaPhone;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
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
        @Valid Document document,
        UUID sourceOrderId,
        @Valid DeferredOrder deferredOrder) {

    public CreateSaleRequest(
            UUID branchId,
            UUID cashShiftId,
            UUID customerId,
            BigDecimal taxTotal,
            List<Item> items,
            List<PaymentLine> payments,
            UUID confirmationId,
            Document document) {
        this(branchId, cashShiftId, customerId, taxTotal, items, payments,
                confirmationId, document, null, null);
    }

    public CreateSaleRequest(
            UUID branchId,
            UUID cashShiftId,
            UUID customerId,
            BigDecimal taxTotal,
            List<Item> items,
            List<PaymentLine> payments,
            UUID confirmationId) {
        this(branchId, cashShiftId, customerId, taxTotal, items, payments,
                confirmationId, null, null, null);
    }

    public CreateSaleRequest(
            UUID branchId,
            UUID cashShiftId,
            UUID customerId,
            BigDecimal taxTotal,
            List<Item> items,
            List<PaymentLine> payments) {
        this(branchId, cashShiftId, customerId, taxTotal, items, payments,
                UUID.randomUUID(), null, null, null);
    }

    public record DeferredOrder(
            @NotBlank @Size(max = 124) String idempotencyKey,
            @NotNull DeliveryMethod deliveryMethod,
            @NotNull TransportMode transportMode,
            @Valid DeliveryAddress deliveryAddress,
            @Valid NotificationContact notificationContact) {}

    public record DeliveryAddress(
            @NotBlank @Size(max = 200) String recipientName,
            @NotBlank @Size(max = 30) @GuatemalaPhone String recipientPhone,
            @NotBlank @Size(max = 300) String line1,
            @Size(max = 300) String line2,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String stateOrDepartment,
            @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 100) String country,
            @Size(max = 500) String references) {}

    public record NotificationContact(
            @NotBlank @Size(max = 30) String emailMode,
            @Email @Size(max = 254) String email) {}

    public record Document(
            @NotNull SaleDocumentType type,
            @Size(max = 100) String taxId,
            @Size(max = 300) String legalName,
            @Size(max = 500) String fiscalAddress) {}

    public record Item(
            @NotNull UUID productId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
            @DecimalMin("0.00") BigDecimal discount,
            List<@Valid InventoryTrackingSelectionRequest> trackingSelections) {

        public Item(UUID productId, BigDecimal quantity, BigDecimal discount) {
            this(productId, quantity, discount, List.of());
        }
    }

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
