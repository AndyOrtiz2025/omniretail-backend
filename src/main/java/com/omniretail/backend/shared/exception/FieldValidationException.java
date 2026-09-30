package com.omniretail.backend.shared.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/**
 * Errores de validacion por campo detectados en un service (reglas que dependen de otros campos o del
 * estado, como la politica de contrasenas). Se responde igual que un {@code @Valid} fallido: 400
 * {@code VALIDATION_ERROR} con {@code fields}.
 */
@Getter
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public FieldValidationException(Map<String, String> fields) {
        super("La solicitud tiene datos invalidos.");
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static FieldValidationException of(String field, String message) {
        return new FieldValidationException(Map.of(field, message));
    }
}
