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
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment extends TenantScopedEntity {

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "sale_id", updatable = false)
    private UUID saleId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false)
    private PaymentMethod method;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "bank_account_id", updatable = false)
    private UUID bankAccountId;

    @Column(name = "reference", updatable = false, columnDefinition = "TEXT")
    private String reference;

    @Column(name = "externally_verified", updatable = false)
    private Boolean externallyVerified;

    @Column(name = "verified_by_user_id", updatable = false)
    private UUID verifiedByUserId;

    @Column(name = "verified_at", updatable = false)
    private Instant verifiedAt;
}
