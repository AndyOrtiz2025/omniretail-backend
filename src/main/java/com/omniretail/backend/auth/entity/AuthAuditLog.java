package com.omniretail.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Evento de auditoria de autenticacion (AuditLog.ts con {@code entityType = "AuthAccount"}).
 * Append-only: no extiende BaseEntity porque no tiene {@code updated_at} ni setters.
 */
@Entity
@Table(name = "auth_audit_logs")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "auth_account_id", updatable = false)
    private UUID authAccountId;

    @NotBlank
    @Size(max = 60)
    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    /** Hora del reloj de auth (authClock), no de la base: el bloqueo por intentos se calcula con estas fechas. */
    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
