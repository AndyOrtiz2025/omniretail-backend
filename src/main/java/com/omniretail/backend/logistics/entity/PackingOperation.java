package com.omniretail.backend.logistics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

/** Evidencia append-only de una mutacion idempotente de Packing. */
@Entity
@Table(name = "packing_operations")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackingOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "packing_id", nullable = false, updatable = false)
    private UUID packingId;

    @NotBlank
    @Size(max = 128)
    @Column(name = "operation_id", nullable = false, updatable = false, length = 128)
    private String operationId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 30)
    private PackingOperationType operationType;

    @NotBlank
    @Column(name = "fingerprint", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String fingerprint;

    @NotNull
    @PositiveOrZero
    @Column(name = "result_version", nullable = false, updatable = false)
    private Long resultVersion;

    @NotBlank
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "result_packing",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb")
    private String resultPacking;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
