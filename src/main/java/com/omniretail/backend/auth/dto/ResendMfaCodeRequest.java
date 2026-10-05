package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Reenvio del codigo por correo del desafio de login. Cualquier otro campo se rechaza con 400. */
public record ResendMfaCodeRequest(
        @Schema(description = "challengeToken devuelto por el login.") String challengeToken,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
