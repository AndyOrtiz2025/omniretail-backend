package com.omniretail.backend.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inicio de sesion con Google.
 *
 * @param clientId Client ID web de Google Cloud (GOOGLE_CLIENT_ID): el {@code aud} que deben traer los ID
 *     token. Opcional: vacio, la app arranca igual y {@code POST /auth/google} responde 503.
 */
@ConfigurationProperties("app.google")
public record GoogleAuthProperties(String clientId) {

    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }
}
