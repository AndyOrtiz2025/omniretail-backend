package com.omniretail.backend.shared.notification;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cuenta Gmail del tenant para sus correos operativos. La contrasena de aplicacion solo existe cifrada
 * ({@link EmailCredentialCrypto}); nunca se devuelve por la API.
 */
@Entity
@Table(name = "tenant_email_sender_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantEmailSenderConfig extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private EmailSenderProvider provider;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    @Column(name = "sender_name", nullable = false)
    private String senderName;

    @Column(name = "encrypted_credential", nullable = false)
    private String encryptedCredential;

    @Column(name = "credential_iv", nullable = false)
    private String credentialIv;

    @Column(name = "encryption_key_version", nullable = false)
    private int encryptionKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EmailSenderStatus status;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    /** Sin la contrasena ni su texto cifrado. */
    @Override
    public String toString() {
        return "TenantEmailSenderConfig[tenantId=" + getTenantId() + ", status=" + status + "]";
    }
}
