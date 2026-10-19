package com.omniretail.backend.commercial;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reintenta los correos comerciales vencidos. Seguro con varias instancias (SKIP LOCKED en el repositorio). */
@Slf4j
@Component
@ContactRequestsEnabled
@RequiredArgsConstructor
class ContactRequestMailRetryJob {

    private final ContactRequestMailService mailService;

    @Scheduled(
            fixedDelayString = "${app.commercial.contact-requests.mail-retry-interval:PT1M}",
            initialDelayString = "${app.commercial.contact-requests.mail-retry-interval:PT1M}")
    void run() {
        try {
            int processed = mailService.retryDue();
            if (processed > 0) {
                log.info("Reintento de correos comerciales: {} solicitudes", processed);
            }
        } catch (RuntimeException ex) {
            log.error("Fallo el reintento de correos comerciales ({})", ex.getClass().getSimpleName());
        }
    }
}
