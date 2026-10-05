package com.omniretail.backend.shared.notification;

/**
 * Se publica dentro de la transaccion de la operacion de negocio. {@link EmailRequestedListener} registra
 * el envio como PENDING en esa misma transaccion; el correo sale solo si esta hace commit.
 */
public record EmailRequestedEvent(EmailMessage message) {
}
