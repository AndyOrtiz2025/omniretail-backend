package com.omniretail.backend.shared.notification;

/** Correo de texto simple. Nunca debe llevar contrasenas ni otros secretos de la cuenta. */
public record EmailMessage(String to, String subject, String body) {
}
