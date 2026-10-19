package com.omniretail.backend.administration.service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Aplica, al arrancar, la configuracion de complementos del despliegue. Solo existe con
 * {@code app.provisioning.subscription.enabled=true}. Una configuracion incompleta o invalida (o un tenant
 * inexistente) detiene el arranque con un mensaje claro: nunca se aplica a medias ni a "todos" los tenants.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.provisioning.subscription", name = "enabled", havingValue = "true")
class SubscriptionProvisioningRunner implements ApplicationRunner {

    private static final String PREFIX = "app.provisioning.subscription.";

    private final SubscriptionProvisioningProperties properties;
    private final SubscriptionProvisioningService provisioning;

    @Override
    public void run(ApplicationArguments args) {
        String operationId = properties.operationId();
        if (!operationId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("Aprovisionamiento de complementos: " + PREFIX
                    + "operation-id es obligatorio (1 a 64 caracteres: letras, digitos, punto, guion).");
        }
        UUID tenantId = parseTenant(properties.tenantId());
        List<String> codes = properties.addonCodes().isBlank()
                ? List.of()
                : Arrays.stream(properties.addonCodes().split(",")).map(String::trim).toList();
        SubscriptionProvisioningService.Result result = provisioning.applyOnce(tenantId, operationId, codes);
        log.info("Aprovisionamiento de complementos operationId={} resultado={}", operationId, result);
    }

    private static UUID parseTenant(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Aprovisionamiento de complementos: " + PREFIX + "tenant-id es obligatorio y debe ser un UUID.");
        }
    }
}
