package com.omniretail.backend.shared.security;

import com.omniretail.backend.shared.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Guard de entitlement: verifica que el plan del tenant incluya una capacidad SaaS. Mismo orden de
 * validacion que entitlementGuards.ts del frontend.
 *
 * <p>Es la primera capa que se evalua y se usa ADEMAS de {@link RequirePermission}, nunca en su
 * lugar: el plan dice que el negocio tiene la funcion; el permiso dice que este usuario puede usarla.
 *
 * <p>En endpoints publicos se llama con el tenant resuelto por slug; en endpoints autenticados,
 * con {@code CurrentUser.require().tenantId()}. El tenant nunca se toma del body, query ni headers.
 */
@Component
@RequiredArgsConstructor
public class TenantCapabilityGuard {

    private final TenantEntitlementResolver entitlementResolver;

    /** Lanza 403 si la suscripcion o el plan no estan activos, o si el plan no incluye la capacidad. */
    public void ensureTenantCapability(UUID tenantId, SaasCapability capability) {
        TenantEntitlements entitlements = entitlementResolver.resolve(tenantId);
        if (!entitlements.subscriptionActive()) {
            throw BusinessException.forbidden("SUBSCRIPTION_INACTIVE", "La suscripción del negocio no está activa.");
        }
        if (!entitlements.planActive()) {
            throw BusinessException.forbidden("PLAN_INACTIVE", "El plan del negocio no está activo.");
        }
        if (!entitlements.capabilities().contains(capability)) {
            throw BusinessException.forbidden("CAPABILITY_REQUIRED", "Esta función no está incluida en tu plan actual.");
        }
    }
}
