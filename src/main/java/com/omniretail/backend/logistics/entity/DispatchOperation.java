package com.omniretail.backend.logistics.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="dispatch_operations") @Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class DispatchOperation {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @NotNull @Column(name="tenant_id", nullable=false, updatable=false) private UUID tenantId;
 @NotNull @Column(name="branch_id", nullable=false, updatable=false) private UUID branchId;
 @NotNull @Column(name="dispatch_id", nullable=false, updatable=false) private UUID dispatchId;
 @NotBlank @Size(max=128) @Column(name="operation_id", nullable=false, updatable=false, length=128) private String operationId;
 @NotBlank @Column(name="fingerprint", nullable=false, updatable=false, columnDefinition="TEXT") private String fingerprint;
 @NotBlank @JdbcTypeCode(SqlTypes.JSON) @Column(name="result_dispatch", nullable=false, updatable=false, columnDefinition="jsonb") private String resultDispatch;
 @CreationTimestamp @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
}
