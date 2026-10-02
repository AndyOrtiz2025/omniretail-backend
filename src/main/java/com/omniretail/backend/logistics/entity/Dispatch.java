package com.omniretail.backend.logistics.entity;

import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity @Table(name = "dispatches") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Dispatch extends TenantScopedEntity {
    @NotNull @Column(name="branch_id", nullable=false, updatable=false) private UUID branchId;
    @NotNull @Enumerated(EnumType.STRING) @Column(name="source_type", nullable=false, updatable=false, length=20) private DispatchSourceType sourceType;
    @NotNull @Column(name="source_id", nullable=false, updatable=false) private UUID sourceId;
    @Column(name="order_id", updatable=false) private UUID orderId;
    @NotNull @Column(name="packing_id", nullable=false, updatable=false) private UUID packingId;
    @NotNull @Builder.Default @Enumerated(EnumType.STRING) @Column(name="status", nullable=false, length=20) private DispatchStatus status = DispatchStatus.dispatched;
    @NotNull @Enumerated(EnumType.STRING) @Column(name="transport_mode", nullable=false, updatable=false, length=20) private TransportMode transportMode;
    @Size(max=200) @Column(name="carrier_name", length=200, updatable=false) private String carrierName;
    @Size(max=200) @Column(name="tracking_number", length=200, updatable=false) private String trackingNumber;
    @NotNull @Column(name="dispatched_by_user_id", nullable=false, updatable=false) private UUID dispatchedByUserId;
    @NotNull @Column(name="dispatched_at", nullable=false, updatable=false) private Instant dispatchedAt;
    @Column(name="delivered_at") private Instant deliveredAt;
}
