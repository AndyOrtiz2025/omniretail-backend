package com.omniretail.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Verificacion de correo de un cliente recien registrado. Solo guarda el hash del token; el token en
 * claro viaja unicamente en el correo. Sin {@code updated_at}: no extiende BaseEntity.
 */
@Entity
@Table(name = "email_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @NotNull
    @Size(max = 64)
    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;
}
