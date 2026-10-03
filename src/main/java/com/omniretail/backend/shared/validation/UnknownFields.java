package com.omniretail.backend.shared.validation;

import com.omniretail.backend.shared.exception.FieldValidationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rechazo de campos no declarados, solo para los DTOs que lo piden (la configuracion global de Jackson
 * sigue ignorandolos). El DTO captura lo desconocido con un componente {@code @JsonAnySetter} y el
 * service llama a {@link #reject(Map)} antes de validar el resto.
 */
public final class UnknownFields {

    static final String MESSAGE = "Campo no permitido.";

    private UnknownFields() {
    }

    /** 400 {@code VALIDATION_ERROR} con un error por cada campo no permitido. */
    public static void reject(Map<String, Object> unknownFields) {
        if (unknownFields == null || unknownFields.isEmpty()) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        unknownFields.keySet().forEach(field -> errors.put(field, MESSAGE));
        throw new FieldValidationException(errors);
    }
}
