package com.omniretail.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * @param tenantSlug solo habilita cuentas de cliente de esa tienda; sin el, solo se consideran
 *                   cuentas de empleado.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "El correo es obligatorio.")
        // Vacio lo reporta solo @NotBlank: ni @Email ni @Pattern fallan con "".
        @Email(message = "Ingrese un correo con formato válido.")
        @Pattern(regexp = "^$|" + RegisterCustomerRequest.EMAIL_PATTERN, message = "Ingrese un correo con formato válido.")
        @Size(max = 254, message = "El correo no puede superar 254 caracteres.")
        String email,
        @Size(max = 100) String tenantSlug) {

    /** Normaliza antes de validar: @Email revisa el email ya limpio. */
    public ForgotPasswordRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
