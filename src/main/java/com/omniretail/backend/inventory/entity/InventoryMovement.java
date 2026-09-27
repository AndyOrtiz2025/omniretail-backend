package com.omniretail.backend.inventory.entity;

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
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** Movimiento historico de inventario, modelado como registro append-only. */
@Entity
@Table(name = "inventory_movements")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryMovement {

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
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 10)
    private InventoryMovementType type;

    @NotBlank
    @Size(max = 200)
    @Column(name = "reason", nullable = false, updatable = false, length = 200)
    private String reason;

    @NotNull
    @Column(name = "quantity", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "quantity_before", updatable = false, precision = 12, scale = 3)
    private BigDecimal quantityBefore;

    @Column(name = "quantity_after", updatable = false, precision = 12, scale = 3)
    private BigDecimal quantityAfter;

    @Column(name = "from_location_id", updatable = false)
    private UUID fromLocationId;

    @Column(name = "to_location_id", updatable = false)
    private UUID toLocationId;

    @Size(max = 50)
    @Column(name = "reference_type", updatable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "performed_by_user_id", updatable = false)
    private UUID performedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
