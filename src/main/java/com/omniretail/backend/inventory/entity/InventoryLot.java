package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Foundation Phase 1: identidad de un lote; todavia no participa en operaciones de stock. */
@Entity
@Table(name = "inventory_lots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryLot extends TenantScopedEntity {

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "lot_number", nullable = false, length = 100, updatable = false)
    private String lotNumber;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;
}
