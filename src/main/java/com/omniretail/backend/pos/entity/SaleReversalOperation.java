package com.omniretail.backend.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Registro append-only de una mutacion idempotente de venta POS. */
@Entity
@Table(name = "sale_reversal_operations")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleReversalOperation {

    @Id
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 30)
    private SaleReversalOperationType operationType;

    @NotNull
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @NotBlank
    @Size(max = 64)
    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @NotBlank
    @Size(max = 1000)
    @Column(name = "reason", nullable = false, updatable = false, length = 1000)
    private String reason;

    @NotNull
    @Column(name = "executed_by_user_id", nullable = false, updatable = false)
    private UUID executedByUserId;

    @NotBlank
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String resultPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
