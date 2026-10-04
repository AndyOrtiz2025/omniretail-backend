package com.omniretail.backend.auth.entity;

import com.omniretail.backend.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Verificacion en dos pasos de un usuario (MfaEnrollment.ts). Pasa por dos fases: pendiente
 * ({@code enabled=false} con secreto) y activa ({@code enabled=true}) tras confirmar un codigo.
 *
 * <p>{@code secretCiphertext} es el secreto TOTP cifrado (ver MfaCrypto); es null cuando el MFA esta
 * desactivado. Sin {@code @Builder} ni {@code @ToString} a proposito: nada debe imprimir el secreto.
 * {@code lastUsedStep} es el ultimo paso TOTP aceptado; un codigo de ese paso o anterior se rechaza.
 */
@Entity
@Table(name = "mfa_enrollments")
@Getter
@Setter
@NoArgsConstructor
public class MfaEnrollment extends BaseEntity {

    @NotNull
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private MfaMethod method = MfaMethod.totp;

    @Column(name = "secret_ciphertext")
    private String secretCiphertext;

    @NotNull
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Intentos fallidos de confirmar la activacion pendiente. */
    @NotNull
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @Column(name = "last_used_step")
    private Long lastUsedStep;
}
