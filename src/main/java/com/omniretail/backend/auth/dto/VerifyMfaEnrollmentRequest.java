package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Codigo de la app para confirmar la activacion. Cualquier otro campo se rechaza con 400. */
public record VerifyMfaEnrollmentRequest(
        @Schema(example = "123456") String code,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
