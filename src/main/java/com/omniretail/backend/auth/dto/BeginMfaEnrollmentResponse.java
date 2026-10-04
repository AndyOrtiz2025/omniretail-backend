package com.omniretail.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Datos para registrar la cuenta en la app autenticadora. El secreto solo se devuelve aqui, mientras la
 * activacion esta pendiente; despues de confirmarla nunca vuelve a salir del servidor.
 */
public record BeginMfaEnrollmentResponse(
        @Schema(example = "totp") String method,
        @Schema(description = "Secreto en Base32, para ingresarlo a mano en la app.") String secret,
        @Schema(description = "URI otpauth:// para generar el código QR en el frontend.") String otpauthUri) {
}
