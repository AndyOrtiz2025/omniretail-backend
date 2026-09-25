package com.omniretail.backend.administration.service;

import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * MVP: hasta que existan las tablas plans y tenant_subscriptions; reemplazar esta clase sin tocar
 * TenantCapabilityGuard ni sus llamadas.
 *
 * <p>Mientras tanto todo tenant tiene suscripcion y plan activos y todas las capacidades.
 */
@Service
public class DefaultTenantEntitlementResolver implements TenantEntitlementResolver {

    @Override
    public TenantEntitlements resolve(UUID tenantId) {
        return new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class));
    }
}
