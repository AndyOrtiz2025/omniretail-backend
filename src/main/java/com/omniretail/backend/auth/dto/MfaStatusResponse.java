package com.omniretail.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Estado del MFA de la sesion (getMfaStatus de AuthRepository.ts). {@code method} es null si el usuario
 * nunca inicio una activacion (el frontend lo trata como {@code null}).
 */
public record MfaStatusResponse(
        boolean enabled,
        @Schema(example = "totp", nullable = true) String method) {
}
