package com.omniretail.backend.shared.notification;

/** Se publica dentro de una transaccion; el correo sale solo si esa transaccion hace commit. */
public record EmailRequestedEvent(EmailMessage message) {
}
