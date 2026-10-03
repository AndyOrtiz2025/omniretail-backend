package com.omniretail.backend.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Lo unico que el cliente puede editar de su perfil (allowlist de {@code UpdateCustomerProfileInput}).
 * El correo no se edita aqui: es la credencial de inicio de sesion. Cualquier otro campo se rechaza
 * con 400; las reglas y mensajes (profile.validation.ts) se validan en el service.
 *
 * @param phone opcional; ausente o vacio lo deja sin telefono.
 */
public record UpdateCustomerProfileRequest(
        @Schema(example = "Ana López") String name,
        @Schema(example = "55551234", description = "Exactamente 8 dígitos, sin código de país.") String phone,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
