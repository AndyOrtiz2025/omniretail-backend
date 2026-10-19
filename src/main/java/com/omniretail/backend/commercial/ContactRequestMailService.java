package com.omniretail.backend.commercial;

import com.omniretail.backend.shared.notification.EmailDeliveryException;
import com.omniretail.backend.shared.notification.EmailMessage;
import com.omniretail.backend.shared.notification.PlatformEmailSender;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envia los correos de una solicitud, siempre por {@link PlatformEmailSender} (nunca el Gmail de un tenant) y
 * despues del commit del registro. Cada correo tiene su propio estado: un reintento solo toca el que no
 * esta SENT, de modo que lo ya entregado no se reenvia. SENT significa que el transporte SMTP acepto el mensaje;
 * un fallo deja FAILED con codigo saneado y fecha del proximo intento, con tope de intentos.
 *
 * <p>Un fallo de SMTP nunca modifica ni borra la solicitud. Logs: requestId, canal y codigo; nunca destinatarios
 * ni contenido.
 */
@Slf4j
@Service
@ContactRequestsEnabled
@RequiredArgsConstructor
public class ContactRequestMailService {

    private final ContactRequestRepository repository;
    private final PlatformEmailSender sender;
    private final ContactRequestProperties properties;

    /** Primer intento, tras el commit y en su propia transaccion. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(UUID requestId) {
        repository.findByIdForUpdate(requestId).ifPresent(request -> attempt(request, true));
    }

    /** Reintenta lo vencido. Lo invoca {@link ContactRequestMailRetryJob}. */
    @Transactional
    public int retryDue() {
        List<ContactRequest> due = repository.claimDue(
                Instant.now(), properties.mailMaxAttempts(), 20);
        due.forEach(request -> attempt(request, false));
        return due.size();
    }

    private void attempt(ContactRequest request, boolean firstAttempt) {
        if (request.getStatus() != ContactRequestStatus.RECEIVED || request.getAnonymizedAt() != null) {
            return;
        }
        Instant now = Instant.now();
        if (isDue(request.getTeamMailStatus(), request.getTeamMailAttempts(),
                request.getTeamMailNextAttemptAt(), firstAttempt, now)) {
            deliver(request, "team", ContactRequestMails.teamNotification(request, properties.teamNotificationTo()),
                    now, outcome -> {
                        request.setTeamMailAttempts(request.getTeamMailAttempts() + 1);
                        request.setTeamMailStatus(outcome.status());
                        request.setTeamMailErrorCode(outcome.errorCode());
                        request.setTeamMailSentAt(outcome.sentAt());
                        request.setTeamMailNextAttemptAt(outcome.nextAttemptAt());
                    }, request.getTeamMailAttempts() + 1);
        }
        if (isDue(request.getConfirmationMailStatus(), request.getConfirmationMailAttempts(),
                request.getConfirmationMailNextAttemptAt(), firstAttempt, now)) {
            deliver(request, "confirmation", ContactRequestMails.confirmation(request), now, outcome -> {
                request.setConfirmationMailAttempts(request.getConfirmationMailAttempts() + 1);
                request.setConfirmationMailStatus(outcome.status());
                request.setConfirmationMailErrorCode(outcome.errorCode());
                request.setConfirmationMailSentAt(outcome.sentAt());
                request.setConfirmationMailNextAttemptAt(outcome.nextAttemptAt());
            }, request.getConfirmationMailAttempts() + 1);
        }
    }

    private boolean isDue(
            ContactMailStatus status, int attempts, Instant nextAttemptAt, boolean firstAttempt, Instant now) {
        if (attempts >= properties.mailMaxAttempts()) {
            return false;
        }
        if (firstAttempt) {
            return status == ContactMailStatus.PENDING;
        }
        return (status == ContactMailStatus.PENDING || status == ContactMailStatus.FAILED)
                && nextAttemptAt != null
                && !nextAttemptAt.isAfter(now);
    }

    private record Outcome(ContactMailStatus status, String errorCode, Instant sentAt, Instant nextAttemptAt) {
    }

    private void deliver(
            ContactRequest request,
            String channel,
            EmailMessage message,
            Instant now,
            Consumer<Outcome> apply,
            int attemptNumber) {
        try {
            sender.send(message);
            apply.accept(new Outcome(ContactMailStatus.SENT, null, now, null));
        } catch (RuntimeException ex) {
            String code = ex instanceof EmailDeliveryException delivery
                    ? delivery.getCode()
                    : EmailDeliveryException.TRANSPORT_ERROR;
            Instant next = attemptNumber < properties.mailMaxAttempts()
                    ? now.plus(backoff(attemptNumber))
                    : null;
            apply.accept(new Outcome(ContactMailStatus.FAILED, code, null, next));
            log.warn("Correo comercial no enviado requestId={} channel={} attempt={} reason={}",
                    request.getId(), channel, attemptNumber, code);
        }
    }

    private Duration backoff(int attempts) {
        return properties.mailBaseBackoff().multipliedBy(1L << Math.min(attempts - 1, 10));
    }
}
