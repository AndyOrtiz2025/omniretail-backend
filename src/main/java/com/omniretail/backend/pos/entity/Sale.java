package com.omniretail.backend.pos.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sales")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sale extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotBlank
    @Size(max = 50)
    @Column(name = "number", nullable = false, updatable = false)
    private String number;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "source_order_id", updatable = false)
    private UUID sourceOrderId;

    @Column(name = "confirmation_id", updatable = false)
    private UUID confirmationId;

    @Column(name = "confirmation_fingerprint", updatable = false, columnDefinition = "TEXT")
    private String confirmationFingerprint;

    @NotNull
    @Column(name = "cash_shift_id", nullable = false, updatable = false)
    private UUID cashShiftId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SaleStatus status = SaleStatus.completed;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", updatable = false)
    private SaleDocumentType documentType;

    @Column(name = "document_tax_id", updatable = false, columnDefinition = "TEXT")
    private String documentTaxId;

    @Column(name = "document_legal_name", updatable = false, columnDefinition = "TEXT")
    private String documentLegalName;

    @Column(name = "document_fiscal_address", updatable = false, columnDefinition = "TEXT")
    private String documentFiscalAddress;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "subtotal", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @NotNull
    @DecimalMin("0.00")
    @Column(
            name = "discount_total",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 2)
    private BigDecimal discountTotal;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "tax_total", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal taxTotal;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "total", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @NotNull
    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;
}
