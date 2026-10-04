package com.omniretail.backend.auth.service;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de la verificacion en dos pasos. Mismo patron que {@code JwtProperties}: valor de
 * desarrollo en application-dev, obligatoria en prod, y validada al arrancar para fallar temprano.
 *
 * @param encryptionKey clave maestra (MFA_ENCRYPTION_KEY), minimo 32 bytes. De ella se derivan las
 *     subclaves de cifrado del secreto TOTP y de HMAC de los codigos de recuperacion (ver MfaCrypto).
 */
@ConfigurationProperties("app.mfa")
public record MfaProperties(String encryptionKey) {

    static final int MIN_KEY_BYTES = 32;

    public MfaProperties {
        if (encryptionKey == null || encryptionKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.mfa.encryption-key (MFA_ENCRYPTION_KEY) es obligatoria y debe tener al menos "
                            + MIN_KEY_BYTES + " bytes.");
        }
    }

    /** Nunca se imprime la clave (un record la incluiria en su toString). */
    @Override
    public String toString() {
        return "MfaProperties[encryptionKey=***]";
    }
}
