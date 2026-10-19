package com.omniretail.backend.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tope de logins fallidos por IP + email normalizado (ver {@link LoginFailureLimiter}). */
@ConfigurationProperties("app.security.login-failure-limit")
public record LoginFailureLimitProperties(
        boolean enabled,
        int maxFailures,
        int windowSeconds,
        int maxTrackedKeys) {
}
