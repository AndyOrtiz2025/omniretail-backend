package com.omniretail.backend.administration.service;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aprovisionamiento interno de los complementos de un tenant. No tiene controller: lo invoca
 * {@link SubscriptionProvisioningRunner} desde la configuracion del despliegue.
 *
 * <p>Idempotente: cada {@code operationId} se registra en {@code subscription_provisioning_operations} en la misma
 * transaccion que el cambio; repetirlo no hace nada, aunque los valores configurados hayan cambiado. Si el cambio
 * falla, la bitacora tambien se revierte y se puede reintentar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionProvisioningService {

    public enum Result {
        APPLIED,
        ALREADY_APPLIED
    }

    private static final String LEDGER_SQL = """
            INSERT INTO subscription_provisioning_operations (operation_id, tenant_id, addon_codes, applied_at)
            VALUES (:operationId, :tenantId, :addonCodes, :appliedAt)
            ON CONFLICT (operation_id) DO NOTHING
            """;

    private final TenantSubscriptionService subscriptions;
    private final EntityManager entityManager;

    @Transactional
    public Result applyOnce(UUID tenantId, String operationId, List<String> addonCodes) {
        // Valida antes de tocar la bitacora: un codigo desconocido no consume el operationId.
        List<String> normalized = SubscriptionAddonCatalog.normalize(addonCodes);
        int inserted = entityManager.createNativeQuery(LEDGER_SQL)
                .setParameter("operationId", operationId)
                .setParameter("tenantId", tenantId)
                .setParameter("addonCodes", String.join(",", normalized))
                .setParameter("appliedAt", Timestamp.from(Instant.now()))
                .executeUpdate();
        if (inserted == 0) {
            return Result.ALREADY_APPLIED;
        }
        subscriptions.configureAddons(tenantId, normalized);
        log.info("Complementos aprovisionados tenantId={} operationId={} addons={}", tenantId, operationId, normalized);
        return Result.APPLIED;
    }
}
