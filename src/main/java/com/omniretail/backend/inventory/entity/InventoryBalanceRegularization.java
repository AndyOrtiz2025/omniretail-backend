package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Auditoria e idempotencia de la consolidacion del balance heredado sin ubicacion en la ubicacion
 * operativa asignada. Una fila por operacion confirmada; no se modifica salvo para enlazar el movimiento.
 */
@Entity
@Table(name = "inventory_balance_regularizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryBalanceRegularization extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** Siempre null: el origen es el balance heredado sin ubicacion. */
    @Column(name = "from_location_id", updatable = false)
    private UUID fromLocationId;

    @NotNull
    @Column(name = "to_location_id", nullable = false, updatable = false)
    private UUID toLocationId;

    @NotNull
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @NotNull
    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @NotNull
    @Column(name = "reason", nullable = false, updatable = false, length = 200)
    private String reason;

    @NotNull
    @Column(name = "performed_by_user_id", nullable = false, updatable = false)
    private UUID performedByUserId;

    @NotNull
    @Column(name = "moved_quantity", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal movedQuantity;

    @NotNull
    @Column(
            name = "moved_reserved_quantity",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 3)
    private BigDecimal movedReservedQuantity;

    @NotNull
    @Column(
            name = "destination_quantity_before",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 3)
    private BigDecimal destinationQuantityBefore;

    @NotNull
    @Column(
            name = "destination_quantity_after",
            nullable = false,
            updatable = false,
            precision = 12,
            scale = 3)
    private BigDecimal destinationQuantityAfter;

    @Column(name = "movement_id")
    private UUID movementId;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload", nullable = false, columnDefinition = "jsonb")
    private String resultPayload;
}
