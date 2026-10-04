package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Desactivar el MFA exige la contrasena actual (disableMfa de AuthRepository.ts). */
public record DisableMfaRequest(
        String currentPassword,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
