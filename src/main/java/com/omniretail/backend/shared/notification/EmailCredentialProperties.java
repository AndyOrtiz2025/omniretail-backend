package com.omniretail.backend.shared.notification;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clave maestra con la que se cifran las credenciales SMTP de los tenants y los cuerpos de correo
 * persistidos. Mismo patron que {@code MfaProperties}: valor de dev en application-dev, obligatoria en prod
 * y validada al arrancar. Es una clave distinta de la del MFA y de la del JWT.
 *
 * @param encryptionKey EMAIL_CREDENTIAL_ENCRYPTION_KEY, minimo 32 bytes.
 */
@ConfigurationProperties("app.mail.credential")
public record EmailCredentialProperties(String encryptionKey) {

    static final int MIN_KEY_BYTES = 32;

    public EmailCredentialProperties {
        if (encryptionKey == null || encryptionKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.mail.credential.encryption-key (EMAIL_CREDENTIAL_ENCRYPTION_KEY) es obligatoria y debe tener al menos "
                            + MIN_KEY_BYTES + " bytes.");
        }
    }

    /** Nunca se imprime la clave (un record la incluiria en su toString). */
    @Override
    public String toString() {
        return "EmailCredentialProperties[encryptionKey=***]";
    }
}
