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

@Entity
@Table(name = "inventory_balances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryBalance extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @NotNull
    @Builder.Default
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    @NotNull
    @Builder.Default
    @Column(name = "reserved_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    public void add(BigDecimal quantity) {
        this.quantity = this.quantity.add(quantity);
    }

    public void deduct(BigDecimal quantity) {
        BigDecimal resultingQuantity = this.quantity.subtract(quantity);
        if (resultingQuantity.compareTo(reservedQuantity) < 0) {
            throw new IllegalStateException(
                    "La cantidad física no puede ser menor que la cantidad reservada.");
        }
        this.quantity = resultingQuantity;
    }

    public void reserve(BigDecimal quantity) {
        BigDecimal availableQuantity = this.quantity.subtract(this.reservedQuantity);
        if (quantity.compareTo(availableQuantity) > 0) {
            throw new IllegalStateException("Stock disponible insuficiente.");
        }
        this.reservedQuantity = this.reservedQuantity.add(quantity);
    }

    public void consumeReservation(BigDecimal quantity) {
        if (quantity.compareTo(this.reservedQuantity) > 0
                || quantity.compareTo(this.quantity) > 0) {
            throw new IllegalStateException("La reserva excede el balance de inventario.");
        }
        this.reservedQuantity = this.reservedQuantity.subtract(quantity);
        this.quantity = this.quantity.subtract(quantity);
    }

    public void releaseReservation(BigDecimal quantity) {
        if (quantity.compareTo(this.reservedQuantity) > 0) {
            throw new IllegalStateException("La reserva excede el stock reservado.");
        }
        this.reservedQuantity = this.reservedQuantity.subtract(quantity);
    }
}
