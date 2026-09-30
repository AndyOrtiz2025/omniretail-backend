package com.omniretail.backend.auth.dto;

/** @param tenantSlug tienda del cliente, para que el frontend arme la URL de inicio de sesion. */
public record VerifyEmailResponse(String tenantSlug) {
}
