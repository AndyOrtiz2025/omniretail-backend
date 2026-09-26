package com.omniretail.backend.shared.security;

import java.util.Set;

/** Estado comercial del tenant: suscripcion, plan y capacidades que el plan incluye. */
public record TenantEntitlements(boolean subscriptionActive, boolean planActive, Set<SaasCapability> capabilities) {

    public TenantEntitlements {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
