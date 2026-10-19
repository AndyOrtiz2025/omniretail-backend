package com.omniretail.backend.administration.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aprovisionamiento interno de complementos ({@code app.provisioning.subscription.*}). Apagado por defecto. Lo
 * controla quien opera el despliegue (variables de entorno del servidor), nunca un usuario del tenant.
 *
 * @param operationId identifica la operacion; se aplica una sola vez (bitacora en BD). Para cambiar los
 *                    complementos hay que usar un identificador nuevo.
 * @param tenantId    tenant destino, obligatorio: no existe "todos los tenants".
 * @param addonCodes  codigos separados por coma (vacio = ningun complemento).
 */
@ConfigurationProperties("app.provisioning.subscription")
public record SubscriptionProvisioningProperties(
        Boolean enabled, String operationId, String tenantId, String addonCodes) {

    public SubscriptionProvisioningProperties {
        enabled = enabled != null && enabled;
        operationId = operationId == null ? "" : operationId.trim();
        tenantId = tenantId == null ? "" : tenantId.trim();
        addonCodes = addonCodes == null ? "" : addonCodes.trim();
    }
}
