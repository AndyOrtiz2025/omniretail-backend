package com.omniretail.backend.shared.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ciclo de vida de {@code email_delivery}: PENDING (dentro de la transaccion de negocio) -> SENT/FAILED
 * (tras el commit) y reintentos de los fallos de transporte. Un fallo de correo solo queda en este registro:
 * nunca se propaga a la operacion de negocio.
 *
 * <p>Logs: solo tenantId, purpose, destinatario y codigo de motivo; nunca el cuerpo, tokens ni credenciales.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDeliveryService {

    private static final int SUBJECT_MAX = 255;

    /** Payload cifrado de reintento: solo descriptores de adjuntos, nunca los bytes. */
    private record Payload(String subject, String body, String html, List<EmailAttachmentReference> attachments) {
    }

    private final EmailDeliveryRepository deliveries;
    private final TenantEmailSenderConfigRepository senderConfigs;
    private final EmailSenderResolver senderResolver;
    private final EmailCredentialCrypto crypto;
    private final EmailDeliveryProperties properties;
    private final JsonMapper jsonMapper;
    private final List<EmailAttachmentResolver> attachmentResolvers;

    /** Se une a la transaccion de la operacion de negocio: si esta se deshace, el registro tambien. */
    @Transactional
    public UUID recordPending(EmailMessage message) {
        Instant now = Instant.now();
        EmailDelivery.EmailDeliveryBuilder delivery = EmailDelivery.builder()
                .tenantId(message.tenantId())
                .purpose(message.purpose())
                .recipient(message.to())
                .subject(truncate(message.subject()))
                .status(EmailDeliveryStatus.PENDING)
                .createdAt(now);
        if (message.purpose().scope() == EmailPurpose.Scope.TENANT) {
            EmailCredentialCrypto.Encrypted encrypted = crypto.encryptPayload(
                    jsonMapper.writeValueAsString(new Payload(
                            message.subject(), message.body(), message.html(), message.attachments())),
                    message.tenantId());
            delivery.encryptedPayload(encrypted.ciphertext())
                    .payloadIv(encrypted.iv())
                    .encryptionKeyVersion(encrypted.keyVersion())
                    // La via normal es el envio tras commit; el job solo recoge lo que se quedo colgado.
                    .nextAttemptAt(now.plus(properties.initialDelay()));
        }
        return deliveries.save(delivery.build()).getId();
    }

    /** Primer intento, tras el commit y en otro hilo. En su propia transaccion para no depender de la de negocio. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(UUID deliveryId, EmailMessage message) {
        deliveries.findByIdForUpdate(deliveryId)
                .filter(delivery -> delivery.getStatus() == EmailDeliveryStatus.PENDING)
                .ifPresent(delivery -> attempt(delivery, message));
    }

    /** Reintenta lo vencido, con backoff y tope de intentos. Lo invoca {@link EmailDeliveryRetryJob}. */
    @Transactional
    public int retryDue() {
        List<EmailDelivery> due = deliveries.claimDue(
                Instant.now(), properties.maxAttempts(), properties.batchSize());
        for (EmailDelivery delivery : due) {
            EmailMessage message = rebuild(delivery);
            if (message == null) {
                delivery.setStatus(EmailDeliveryStatus.FAILED);
                delivery.setErrorCode(EmailDeliveryException.CREDENTIAL_UNREADABLE);
                delivery.setNextAttemptAt(null);
                continue;
            }
            attempt(delivery, message);
        }
        return due.size();
    }

    private void attempt(EmailDelivery delivery, EmailMessage message) {
        Instant now = Instant.now();
        String failure = null;
        try {
            // Los adjuntos se generan en cada intento (también en reintentos) a partir de su descriptor.
            EmailMessage composed = withResolvedAttachments(delivery.getTenantId(), message);
            senderResolver.resolveSender(delivery.getTenantId(), delivery.getPurpose()).send(composed);
        } catch (EmailDeliveryException ex) {
            failure = ex.getCode();
        } catch (RuntimeException ex) {
            failure = EmailDeliveryException.TRANSPORT_ERROR;
        }
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(now);
        if (failure == null) {
            delivery.setStatus(EmailDeliveryStatus.SENT);
            delivery.setSentAt(now);
            delivery.setErrorCode(null);
            delivery.setNextAttemptAt(null);
            return;
        }
        delivery.setStatus(EmailDeliveryStatus.FAILED);
        delivery.setErrorCode(failure);
        // Solo el fallo de transporte es transitorio. Sin cuenta o con credencial rechazada reintentar no sirve.
        boolean retry = (EmailDeliveryException.TRANSPORT_ERROR.equals(failure)
                        || EmailDeliveryException.ATTACHMENT_UNAVAILABLE.equals(failure))
                && delivery.getEncryptedPayload() != null
                && delivery.getAttempts() < properties.maxAttempts();
        delivery.setNextAttemptAt(retry ? now.plus(backoff(delivery.getAttempts())) : null);
        if (EmailDeliveryException.AUTHENTICATION_FAILED.equals(failure)
                && delivery.getPurpose().scope() == EmailPurpose.Scope.TENANT) {
            markSenderInError(delivery.getTenantId(), now);
        }
        log.warn("Correo no enviado tenantId={} purpose={} recipient={} reason={}",
                delivery.getTenantId(), delivery.getPurpose(), delivery.getRecipient(), failure);
    }

    private EmailMessage withResolvedAttachments(UUID tenantId, EmailMessage message) {
        if (message.attachments().isEmpty()) {
            return message;
        }
        List<EmailAttachmentContent> contents = new ArrayList<>(message.attachments().size());
        for (EmailAttachmentReference reference : message.attachments()) {
            EmailAttachmentResolver resolver = attachmentResolvers.stream()
                    .filter(candidate -> candidate.supports(reference.type()))
                    .findFirst()
                    .orElseThrow(() -> new EmailDeliveryException(EmailDeliveryException.ATTACHMENT_UNAVAILABLE));
            try {
                contents.add(resolver.resolve(tenantId, reference));
            } catch (EmailDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new EmailDeliveryException(EmailDeliveryException.ATTACHMENT_UNAVAILABLE);
            }
        }
        return message.withAttachmentContents(contents);
    }

    private void markSenderInError(UUID tenantId, Instant now) {
        senderConfigs.findByTenantId(tenantId).ifPresent(config -> {
            config.setStatus(EmailSenderStatus.ERROR);
            config.setLastFailureAt(now);
        });
    }

    private Duration backoff(int attempts) {
        return properties.baseBackoff().multipliedBy(1L << Math.min(attempts - 1, 20));
    }

    private EmailMessage rebuild(EmailDelivery delivery) {
        try {
            String json = crypto.decryptPayload(
                    new EmailCredentialCrypto.Encrypted(
                            delivery.getEncryptedPayload(), delivery.getPayloadIv(), delivery.getEncryptionKeyVersion()),
                    delivery.getTenantId());
            Payload payload = jsonMapper.readValue(json, Payload.class);
            return new EmailMessage(delivery.getTenantId(), delivery.getPurpose(), delivery.getRecipient(),
                    payload.subject(), payload.body(), payload.html(), payload.attachments(), List.of());
        } catch (RuntimeException ex) {
            log.warn("Correo irrecuperable tenantId={} purpose={} reason={}", delivery.getTenantId(),
                    delivery.getPurpose(), EmailDeliveryException.CREDENTIAL_UNREADABLE);
            return null;
        }
    }

    private static String truncate(String subject) {
        if (subject == null) {
            return null;
        }
        return subject.length() <= SUBJECT_MAX ? subject : subject.substring(0, SUBJECT_MAX);
    }
}
