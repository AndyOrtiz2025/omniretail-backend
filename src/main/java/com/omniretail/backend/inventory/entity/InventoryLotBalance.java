package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Balance fisico por lote y ubicacion usado por los ajustes trazables de inventario. */
@Entity
@Table(name = "inventory_lot_balances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryLotBalance extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @NotNull
    @Column(name = "lot_id", nullable = false, updatable = false)
    private UUID lotId;

    @NotNull
    @PositiveOrZero
    @Builder.Default
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    @NotNull
    @PositiveOrZero
    @Builder.Default
    @Column(name = "reserved_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    public void add(BigDecimal amount) {
        quantity = quantity.add(amount);
    }

    public void deduct(BigDecimal amount) {
        BigDecimal resultingQuantity = quantity.subtract(amount);
        if (resultingQuantity.compareTo(reservedQuantity) < 0) {
            throw new IllegalStateException("El balance del lote no tiene cantidad disponible suficiente.");
        }
        quantity = resultingQuantity;
    }
}
