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

/**
 * Cuenta externa vinculada a una cuenta de acceso (hoy, Google). {@code subject} es el {@code sub} del ID
 * token: el identificador estable de la cuenta de Google. Nunca se guarda el ID token ni la foto.
 */
@Entity
@Table(name = "auth_external_identities")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthExternalIdentity extends BaseEntity {

    @NotNull
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private ExternalIdentityProvider provider;

    @NotBlank
    @Size(max = 255)
    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @NotBlank
    @Size(max = 254)
    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @NotNull
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;
}
