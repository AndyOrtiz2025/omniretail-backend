package com.omniretail.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * @param rememberMe solo aplica a clientes; para empleados se ignora.
 * @param tenantSlug obligatorio para que un cliente pueda entrar; el tenant nunca se recibe por id.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(max = 200) String password,
        Boolean rememberMe,
        @Size(max = 200) String deviceLabel,
        @Size(max = 100) String tenantSlug) {

    /** Normaliza antes de validar: @Email revisa el email ya limpio. */
    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
