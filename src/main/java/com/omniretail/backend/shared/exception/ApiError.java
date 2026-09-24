package com.omniretail.backend.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Formato uniforme de error para toda la API.
 *
 * @param code   identificador estable que el frontend puede usar sin parsear el mensaje
 *               (p. ej. {@code CAPABILITY_REQUIRED}); {@code null} si no aplica.
 * @param fields errores de validacion por campo; solo presente en errores 400 de validacion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String code,
        String message,
        String path,
        Map<String, String> fields,
        Instant timestamp) {
}
