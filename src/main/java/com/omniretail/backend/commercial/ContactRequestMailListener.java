package com.omniretail.backend.commercial;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Primer intento de envio, solo despues del commit y en otro hilo: la respuesta 202 no espera al SMTP y una
 * solicitud deshecha no envia nada. Si el proceso se detiene antes, el job de reintento recoge el trabajo pendiente.
 */
@Slf4j
@Component
@ContactRequestsEnabled
@RequiredArgsConstructor
class ContactRequestMailListener {

    private final ContactRequestMailService mailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRegistered(ContactRequestRegisteredEvent event) {
        try {
            mailService.process(event.requestId());
        } catch (RuntimeException ex) {
            log.warn("No se pudo procesar el correo comercial requestId={} reason={}",
                    event.requestId(), ex.getClass().getSimpleName());
        }
    }
}
