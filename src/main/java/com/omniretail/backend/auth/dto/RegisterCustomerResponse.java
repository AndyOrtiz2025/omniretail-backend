package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.UserType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta del registro, identica exista o no el correo en la tienda (anti-enumeracion): no lleva el id
 * de la cuenta y {@code user} solo repite los datos enviados (nombre y correo normalizados). Nunca incluye
 * el token de verificacion: viaja solo en el correo.
 */
public record RegisterCustomerResponse(
        @Schema(example = "Si el correo es válido, te enviamos un mensaje para continuar.") String message,
        UserView user) {

    public static final String ACCEPTED_MESSAGE = "Si el correo es válido, te enviamos un mensaje para continuar.";

    public record UserView(
            @Schema(example = "Ana Pérez") String name,
            @Schema(example = "ana@correo.com") String email,
            @Schema(example = "customer") UserType type) {
    }

    /** {@code name} y {@code email} ya normalizados (trim; el correo ademas en minusculas). */
    public static RegisterCustomerResponse accepted(String name, String email) {
        return new RegisterCustomerResponse(ACCEPTED_MESSAGE, new UserView(name, email, UserType.customer));
    }
}
