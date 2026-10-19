package com.omniretail.backend.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limites de ventana fija por IP y grupo de endpoint anonimo. */
@ConfigurationProperties("app.security.public-rate-limit")
public record PublicRateLimitProperties(
        boolean enabled,
        int windowSeconds,
        int maxTrackedClients,
        int loginPerWindow,
        int mfaPerWindow,
        int recoveryPerWindow,
        int registrationPerWindow,
        int checkoutPerWindow,
        int trackingPerWindow,
        int contactPerWindow) {
}
