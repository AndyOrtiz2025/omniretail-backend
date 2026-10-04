package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Inicio de la activacion. Cualquier otro campo se rechaza con 400. */
public record BeginMfaEnrollmentRequest(
        @Schema(description = "Solo `totp` por ahora; `email` responde 400.", example = "totp") String method,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
