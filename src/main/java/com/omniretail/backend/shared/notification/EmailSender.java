package com.omniretail.backend.shared.notification;

/**
 * Envio de correo por un canal concreto. Los modulos no lo llaman directo: publican un
 * {@link EmailRequestedEvent} dentro de su transaccion y {@link EmailDeliveryService} elige el canal segun
 * el {@link EmailPurpose} del mensaje. Un fallo se lanza como {@link EmailDeliveryException}.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
