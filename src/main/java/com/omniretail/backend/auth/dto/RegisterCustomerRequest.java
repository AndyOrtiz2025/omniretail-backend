package com.omniretail.backend.auth.dto;

import jakarta.validation.constraints.Email;

/**
 * Registro publico de un cliente. El tenant sale solo del slug de la URL; nunca del cuerpo.
 * {@code confirmPassword} es solo del formulario del frontend y no se envia.
 *
 * <p>Las reglas y mensajes (register.validation.ts del frontend) se validan en
 * {@code CustomerRegistrationService}, para devolver todos los campos con error en una sola respuesta
 * y validar la contrasena contra el correo. {@code @Email} solo se usa desde ese service.
 */
public record RegisterCustomerRequest(
        String name,
        @Email(regexp = RegisterCustomerRequest.EMAIL_PATTERN) String email,
        String phone,
        String password) {

    /** Mismo patron que EMAIL_PATTERN de register.validation.ts. */
    public static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
}
