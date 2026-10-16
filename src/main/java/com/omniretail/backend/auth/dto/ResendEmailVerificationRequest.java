package com.omniretail.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Pedido de un enlace de verificacion nuevo. Solo clientes verifican su correo, por eso la tienda es
 * obligatoria; el tenant sale del slug, nunca de un id.
 */
public record ResendEmailVerificationRequest(
        @NotBlank(message = "El correo es obligatorio.")
        // Vacio lo reporta solo @NotBlank: ni @Email ni @Pattern fallan con "".
        @Email(message = "Ingrese un correo con formato válido.")
        @Pattern(regexp = "^$|" + RegisterCustomerRequest.EMAIL_PATTERN, message = "Ingrese un correo con formato válido.")
        @Size(max = 254, message = "El correo no puede superar 254 caracteres.")
        @Schema(example = "ana@correo.com")
        String email,
        @NotBlank(message = "La tienda es obligatoria.")
        @Size(max = 100)
        @Schema(example = "mi-tienda")
        String tenantSlug) {

    /** Normaliza antes de validar: @Email revisa el email ya limpio. */
    public ResendEmailVerificationRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        tenantSlug = tenantSlug == null ? null : tenantSlug.trim();
    }
}
