package com.omniretail.backend.shared.security;

/**
 * Capacidades SaaS que un plan puede incluir. Las claves son las mismas que usa el frontend
 * (entitlementGuards.ts); no deben cambiar.
 */
public enum SaasCapability {
    inventory("inventory"),
    purchasing("purchasing"),
    receiving("receiving"),
    pos("pos"),
    ecommerce("ecommerce"),
    delivery("delivery"),
    advancedReports("reports.advanced"),
    traceabilityLots("traceability.lots"),
    traceabilityExpiration("traceability.expiration"),
    traceabilitySerials("traceability.serials"),
    catalogKits("catalog.kits");

    private final String key;

    SaasCapability(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
