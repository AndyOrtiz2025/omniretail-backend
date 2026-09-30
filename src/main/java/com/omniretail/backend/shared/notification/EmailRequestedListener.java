package com.omniretail.backend.shared.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envia el correo despues del commit y en otro hilo: la respuesta HTTP no espera al SMTP, asi el
 * tiempo de respuesta no delata si se envio un correo (p. ej. si una cuenta existe). Un fallo de envio
 * solo se registra en el log; la operacion que lo pidio ya quedo confirmada y no se revierte.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailRequestedListener {

    private final EmailSender emailSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailRequested(EmailRequestedEvent event) {
        try {
            emailSender.send(event.message());
        } catch (RuntimeException ex) {
            // Sin el destinatario ni el cuerpo: el cuerpo lleva un enlace con token.
            log.error("No se pudo enviar el correo \"{}\"", event.message().subject(), ex);
        }
    }
}
