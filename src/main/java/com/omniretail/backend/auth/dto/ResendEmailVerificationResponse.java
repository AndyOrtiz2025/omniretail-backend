package com.omniretail.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Siempre el mismo mensaje: no revela si la cuenta existe, si ya esta verificada ni si se alcanzo el limite. */
public record ResendEmailVerificationResponse(
        @Schema(example = "Si el correo es válido, te enviamos un mensaje para continuar.") String message) {
}
