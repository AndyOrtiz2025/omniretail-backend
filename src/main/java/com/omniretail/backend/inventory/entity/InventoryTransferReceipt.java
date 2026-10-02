package com.omniretail.backend.inventory.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.Setter;

@Entity
@Table(name = "inventory_transfer_receipts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryTransferReceipt extends TenantScopedEntity {

    @NotNull
    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @NotBlank
    @Size(max = 128)
    @Column(name = "confirmation_id", nullable = false, length = 128, updatable = false)
    private String confirmationId;

    @NotBlank
    @Column(
            name = "operation_fingerprint",
            nullable = false,
            columnDefinition = "text",
            updatable = false)
    private String operationFingerprint;

    @NotNull
    @Column(name = "received_by_user_id", nullable = false, updatable = false)
    private UUID receivedByUserId;

    @NotNull
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
