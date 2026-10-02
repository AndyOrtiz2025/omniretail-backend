package com.omniretail.backend.logistics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

/** Registro append-only utilizado para reproducir de forma segura una mutacion de linea. */
@Entity
@Table(name = "picking_item_update_operations")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickingItemUpdateOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Column(name = "picking_item_id", nullable = false, updatable = false)
    private UUID pickingItemId;

    @NotBlank
    @Size(max = 128)
    @Column(name = "operation_id", nullable = false, updatable = false, length = 128)
    private String operationId;

    @NotBlank
    @Column(name = "fingerprint", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String fingerprint;

    @NotBlank
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_item", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String resultItem;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
