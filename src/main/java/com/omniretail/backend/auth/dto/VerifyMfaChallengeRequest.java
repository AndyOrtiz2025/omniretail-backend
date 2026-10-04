package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Segundo paso del login. Cualquier otro campo se rechaza con 400. */
public record VerifyMfaChallengeRequest(
        @Schema(description = "challengeToken devuelto por el login.") String challengeToken,
        @Schema(description = "Código de 6 dígitos de la app o código de recuperación XXXX-XXXX.", example = "123456")
                String code,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
