package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.config.SaasAdministrationProperties;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Restringe las mutaciones del catalogo global al tenant de plataforma configurado. */
@Component
@RequiredArgsConstructor
public class PlatformTenantGuard {

    private final SaasAdministrationProperties properties;
    private final CurrentUser currentUser;

    public void requirePlatformTenant() {
        UUID tenantId = currentUser.require().tenantId();
        String configuredTenantId = properties.platformTenantId();
        if (configuredTenantId == null || configuredTenantId.isBlank()) {
            throw forbidden();
        }
        try {
            if (!tenantId.equals(UUID.fromString(configuredTenantId.trim()))) {
                throw forbidden();
            }
        } catch (IllegalArgumentException exception) {
            throw forbidden();
        }
    }

    private static BusinessException forbidden() {
        return BusinessException.forbidden(
                "PLATFORM_TENANT_REQUIRED",
                "La administracion del catalogo de planes esta restringida al tenant de plataforma.");
    }
}
