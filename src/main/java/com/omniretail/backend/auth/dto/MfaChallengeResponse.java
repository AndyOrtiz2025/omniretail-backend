package com.omniretail.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Respuesta del login cuando el usuario tiene MFA activo (rama {@code mfa_required} de LoginResult): no
 * hay token ni sesion todavia. {@code challengeToken} se envia a {@code POST /auth/mfa/verify}.
 */
public record MfaChallengeResponse(
        @Schema(description = "Siempre true: falta el segundo factor.") boolean mfaRequired,
        @Schema(description = "Token de un solo uso del desafío. Vence en 5 minutos.") String challengeToken,
        @Schema(example = "totp") String method,
        Instant expiresAt) implements LoginOutcome {
}
