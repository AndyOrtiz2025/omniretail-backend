package com.omniretail.backend.commercial;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Solicitud comercial de la landing. Sin caracteres de control ni de formato en los campos de una linea (evita
 * inyeccion de cabeceras y de lineas de log). {@code website} es el honeypot: debe llegar vacio; un robot que lo
 * llena recibe la misma respuesta pero no genera correos.
 */
public record CreateContactRequest(
        @NotBlank @Size(min = 2, max = 100) @Pattern(regexp = CreateContactRequest.SINGLE_LINE, message = "Contiene caracteres no permitidos.")
                String fullName,
        @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = CreateContactRequest.SINGLE_LINE, message = "Contiene caracteres no permitidos.")
                String businessName,
        @NotBlank @Email @Size(max = 254) @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Correo inválido.")
                String email,
        @NotBlank @Pattern(regexp = "^[0-9+()\\- ]{7,20}$", message = "Teléfono inválido.") String phone,
        @NotBlank @Size(max = 60) String industry,
        @NotNull @Size(min = 1, max = 11) List<@NotBlank @Size(max = 40) String> modules,
        @Size(max = 1000) String message,
        @NotNull @AssertTrue(message = "Debes aceptar el tratamiento de tus datos.") Boolean consentAccepted,
        @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Versión inválida.")
                String consentVersion,
        @Size(max = 200) String website,
        @Size(max = 2048) String captchaToken) {

    static final String SINGLE_LINE = "^[^\\p{Cc}\\p{Cf}]+$";
}
