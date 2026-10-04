package com.omniretail.backend.auth.entity;

import com.omniretail.backend.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * Desafio de segundo factor abierto en el login (MfaChallenge.ts, regla R-A16). Solo se guarda el hash
 * del token. Guarda {@code rememberMe} y {@code deviceLabel} del login para crear la sesion final.
 * Queda inutilizable si se consume, se invalida (5 fallos o un login nuevo) o vence.
 */
@Entity
@Table(name = "mfa_challenges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaChallenge extends BaseEntity {

    @NotNull
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @NotBlank
    @Size(max = 64)
    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false)
    private MfaMethod method;

    @NotNull
    @Builder.Default
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @NotNull
    @Builder.Default
    @Column(name = "remember_me", nullable = false, updatable = false)
    private Boolean rememberMe = false;

    @Size(max = 200)
    @Column(name = "device_label", updatable = false)
    private String deviceLabel;

    @NotNull
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    public boolean isUsable(Instant now) {
        return consumedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }
}
