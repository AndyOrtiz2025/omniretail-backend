package com.omniretail.backend.logistics.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

@Entity @Table(name="dispatch_packages") @Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class DispatchPackage {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @NotNull @Column(name="dispatch_id", nullable=false, updatable=false) private UUID dispatchId;
 @NotBlank @Size(max=80) @Column(name="number", nullable=false, updatable=false, length=80) private String number;
 @DecimalMin(value="0.000", inclusive=false) @Column(name="weight", precision=12, scale=3, updatable=false) private BigDecimal weight;
 @Size(max=500) @Column(name="description", length=500, updatable=false) private String description;
 @CreationTimestamp @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
}
