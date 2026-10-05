package com.omniretail.backend.auth.service;

import com.omniretail.backend.shared.exception.FieldValidationException;
import java.util.Map;

/**
 * Codigo de verificacion en dos pasos rechazado en el cambio de contrasena. Responde igual que cualquier
 * {@link FieldValidationException} ({@code fields.mfaCode}); existe como clase propia para que esas
 * transacciones no se deshagan ({@code noRollbackFor}) y el intento fallido quede contado.
 */
public class MfaCodeRejectedException extends FieldValidationException {

    public MfaCodeRejectedException(String message) {
        super(Map.of("mfaCode", message));
    }
}
