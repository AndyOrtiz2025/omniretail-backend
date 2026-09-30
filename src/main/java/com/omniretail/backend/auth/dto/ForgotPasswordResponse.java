package com.omniretail.backend.auth.dto;

/** Siempre el mismo mensaje: no revela si la cuenta existe ni si hubo limite de solicitudes. */
public record ForgotPasswordResponse(String message) {
}
