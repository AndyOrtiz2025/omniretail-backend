package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;

/** Sucursal elegida en el selector del encabezado. Cualquier otro campo se rechaza con 400. */
public record ChangeActiveBranchRequest(
        @Schema(description = "Sucursal activa del tenant, dentro de las permitidas al usuario.") UUID branchId,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
