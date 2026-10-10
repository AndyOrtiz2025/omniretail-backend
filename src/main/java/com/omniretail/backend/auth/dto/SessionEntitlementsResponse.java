package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capacidades y limites del plan del negocio de la sesion. Es lo que el frontend necesita para decidir
 * que acciones mostrar a un empleado: a proposito sin facturas, precios, complementos ni otros planes
 * (eso sigue siendo de {@code admin.plans.read}).
 *
 * @param planCode codigo del plan contratado (por ejemplo {@code basic}).
 * @param isEntitlementActive {@code true} solo si la suscripcion y el plan estan activos.
 * @param capabilities claves de capacidad del plan mas las de sus complementos, aunque no este activo.
 * @param effectiveCapabilities las mismas capacidades si {@code isEntitlementActive}; vacio si no.
 * @param limits limites numericos del plan; omite los que no tienen tope.
 */
public record SessionEntitlementsResponse(
        UUID tenantId,
        String planCode,
        PlanStatus planStatus,
        TenantSubscriptionStatus subscriptionStatus,
        boolean isEntitlementActive,
        List<String> capabilities,
        List<String> effectiveCapabilities,
        Map<String, Integer> limits) {
}
