package com.omniretail.backend.shared.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reintenta los correos operativos vencidos. Seguro con varias instancias (SKIP LOCKED en el repositorio). */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDeliveryRetryJob {

    private final EmailDeliveryService deliveryService;
    private final EmailDeliveryProperties properties;

    @Scheduled(fixedDelayString = "${app.mail.delivery.retry-interval:PT1M}", initialDelayString = "${app.mail.delivery.retry-interval:PT1M}")
    public void run() {
        if (!properties.retryEnabled()) {
            return;
        }
        try {
            int processed = deliveryService.retryDue();
            if (processed > 0) {
                log.info("Reintento de correos: {} procesados", processed);
            }
        } catch (RuntimeException ex) {
            log.error("Fallo el reintento de correos ({})", ex.getClass().getSimpleName());
        }
    }
}
