package com.omniretail.backend.administration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuracion de seguridad para administrar el catalogo SaaS global. */
@ConfigurationProperties("app.saas-administration")
public record SaasAdministrationProperties(String platformTenantId) {
}
