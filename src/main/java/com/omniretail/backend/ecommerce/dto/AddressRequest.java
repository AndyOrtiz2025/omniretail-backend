package com.omniretail.backend.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Datos editables de una direccion (crear y editar). {@code tenantId}, {@code customerId} e
 * {@code isDefault} no se aceptan: salen del JWT o se deciden en el servidor, y cualquier campo no
 * declarado se rechaza con 400. Las reglas y mensajes (address.validation.ts) se validan en el service.
 *
 * @param country se acepta porque el frontend lo envia, pero el servidor siempre guarda "Guatemala".
 */
public record AddressRequest(
        @Schema(example = "Casa") String label,
        @Schema(example = "Ana López") String recipientName,
        @Schema(example = "5a avenida 10-20 zona 1") String line1,
        @Schema(example = "Apartamento 3") String line2,
        @Schema(description = "Municipio del departamento elegido.", example = "Guatemala") String city,
        @Schema(description = "Departamento de Guatemala.", example = "Guatemala") String stateOrDepartment,
        @Schema(description = "Opcional, 5 dígitos.", example = "01001") String postalCode,
        @Schema(description = "Se ignora: siempre se guarda Guatemala.", example = "Guatemala") String country,
        @Schema(example = "Frente al parque") String references,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {
}
