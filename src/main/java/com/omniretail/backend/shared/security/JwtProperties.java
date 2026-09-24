package com.omniretail.backend.shared.security;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de los JWT emitidos por este mismo backend (no hay proveedor OAuth externo).
 *
 * @param secret clave HS256; minimo 32 bytes. Se valida al arrancar para fallar temprano.
 * @param issuer valor del claim {@code iss} que se emite y se exige al validar.
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, String issuer) {

    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) es obligatoria y debe tener al menos " + MIN_SECRET_BYTES + " bytes.");
        }
    }
}
