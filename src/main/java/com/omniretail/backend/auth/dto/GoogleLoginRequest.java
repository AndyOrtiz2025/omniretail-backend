package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inicio de sesion con Google (solo clientes de una tienda).
 *
 * @param idToken credencial (ID token) que entrega Google Identity Services al frontend. Nunca se guarda.
 * @param tenantSlug tienda donde inicia sesion el cliente; obligatorio en la practica (sin el no hay cuenta).
 * @param expectedUserType opcional; si viene, debe ser {@code customer}.
 * @param rememberMe sesion de 30 dias en vez de 2 horas.
 */
public record GoogleLoginRequest(
        @NotBlank @Size(max = 4096) @Schema(description = "ID token (credential) de Google Identity Services.")
                String idToken,
        @Size(max = 100) String tenantSlug,
        UserType expectedUserType,
        Boolean rememberMe,
        @Size(max = 200) String deviceLabel) {

    /** El ID token no debe terminar en un log por accidente. */
    @Override
    public String toString() {
        return "GoogleLoginRequest[idToken=***, tenantSlug=" + tenantSlug + ", expectedUserType=" + expectedUserType
                + ", rememberMe=" + rememberMe + ", deviceLabel=" + deviceLabel + "]";
    }
}
