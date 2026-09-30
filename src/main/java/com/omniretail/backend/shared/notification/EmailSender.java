package com.omniretail.backend.shared.notification;

/**
 * Envio de correo. Los modulos no lo llaman directo: publican un {@link EmailRequestedEvent} dentro de
 * su transaccion y {@link EmailRequestedListener} lo envia despues del commit.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
