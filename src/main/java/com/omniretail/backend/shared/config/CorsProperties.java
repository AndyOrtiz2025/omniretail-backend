package com.omniretail.backend.shared.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Origenes del frontend autorizados a llamar a la API (propiedad {@code app.cors.allowed-origins}). */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
