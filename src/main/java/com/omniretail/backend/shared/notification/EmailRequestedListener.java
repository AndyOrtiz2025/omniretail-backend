package com.omniretail.backend.shared.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dos pasos para que un fallo de correo nunca revierta la operacion de negocio:
 * <ol>
 *   <li>Dentro de la transaccion de la operacion se inserta {@code email_delivery = PENDING}
 *       (si la operacion se deshace, el registro tambien).</li>
 *   <li>Tras el commit y en otro hilo se intenta enviar y se actualiza a SENT/FAILED. La respuesta HTTP no
 *       espera al SMTP, asi el tiempo de respuesta no delata si se envio un correo (anti-enumeracion).</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class EmailRequestedListener {

    private final EmailDeliveryService deliveryService;
    private final ApplicationEventPublisher eventPublisher;

    /** Sincrono a proposito: corre dentro de la transaccion de quien publico el evento. */
    @EventListener
    public void onEmailRequested(EmailRequestedEvent event) {
        eventPublisher.publishEvent(new EmailQueuedEvent(
                deliveryService.recordPending(event.message()), event.message()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEmailQueued(EmailQueuedEvent event) {
        deliveryService.process(event.deliveryId(), event.message());
    }
}
