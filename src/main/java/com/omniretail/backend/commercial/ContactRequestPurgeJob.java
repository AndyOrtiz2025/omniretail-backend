package com.omniretail.backend.commercial;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retencion: pasada {@code retention_until} se anonimizan los datos personales (nombre, negocio, correo, telefono,
 * comentarios, hash de IP). Se conservan el identificador y las fechas para la idempotencia y la auditoria.
 */
@Slf4j
@Component
@ContactRequestsEnabled
class ContactRequestPurgeJob {

    private final ContactRequestRepository repository;
    private final TransactionTemplate transaction;

    ContactRequestPurgeJob(ContactRequestRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${app.commercial.contact-requests.purge-cron:0 30 3 * * *}")
    void run() {
        try {
            int anonymized = purge(Instant.now());
            if (anonymized > 0) {
                log.info("Retencion de solicitudes comerciales: {} anonimizadas", anonymized);
            }
        } catch (RuntimeException ex) {
            log.error("Fallo la retencion de solicitudes comerciales ({})", ex.getClass().getSimpleName());
        }
    }

    int purge(Instant now) {
        Integer count = transaction.execute(status -> repository.anonymizeExpired(now));
        return count == null ? 0 : count;
    }
}
