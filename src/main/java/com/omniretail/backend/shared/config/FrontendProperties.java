package com.omniretail.backend.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** URL publica del frontend (propiedad {@code app.frontend.base-url}), base de los enlaces en correos. */
@ConfigurationProperties("app.frontend")
public record FrontendProperties(String baseUrl) {

    /** Une la base con una ruta que empieza con "/", sin duplicar la barra final de la base. */
    public String link(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }
}
