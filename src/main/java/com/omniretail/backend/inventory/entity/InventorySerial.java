package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Foundation Phase 1: identidad y estado de una unidad serializada, sin transiciones operativas. */
@Entity
@Table(name = "inventory_serials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventorySerial extends TenantScopedEntity {

    @NotNull
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "location_id")
    private UUID locationId;

    @NotNull
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "serial_number", nullable = false, length = 100, updatable = false)
    private String serialNumber;

    @Column(name = "lot_id", updatable = false)
    private UUID lotId;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventorySerialStatus status = InventorySerialStatus.AVAILABLE;

    @Version
    @NotNull
    @PositiveOrZero
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
