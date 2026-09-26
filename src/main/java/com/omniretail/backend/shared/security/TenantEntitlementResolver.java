package com.omniretail.backend.shared.security;

import java.util.UUID;

/**
 * Resuelve el estado comercial de un tenant. Lo implementa el modulo {@code administration}; se
 * define aqui para que {@code shared} no dependa de sus repositorios.
 */
public interface TenantEntitlementResolver {

    TenantEntitlements resolve(UUID tenantId);
}
