package com.omniretail.backend.shared.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Canal TENANT: envia con la cuenta Gmail del tenant (JavaMailSender por tenant, cacheado). El cliente se
 * reconstruye si la configuracion cambia ({@code updatedAt}) o se invalida explicitamente, asi tambien es
 * correcto con varias instancias. Si el tenant no tiene cuenta, o esta en ERROR, NO se cae al remitente de
 * plataforma: se lanza {@code SENDER_NOT_CONFIGURED}.
 */
@Component
@RequiredArgsConstructor
public class GmailSmtpEmailProvider implements TenantEmailSender {

    private record CachedSender(Instant configUpdatedAt, String senderEmail, JavaMailSender sender) {
    }

    private final TenantEmailSenderConfigRepository configs;
    private final EmailCredentialCrypto crypto;
    private final TenantMailSenderFactory senderFactory;

    private final Map<UUID, CachedSender> cache = new ConcurrentHashMap<>();

    @Override
    public void send(EmailMessage message) {
        TenantEmailSenderConfig config = configs.findByTenantId(message.tenantId())
                .filter(found -> found.getStatus() != EmailSenderStatus.ERROR
                        && found.getStatus() != EmailSenderStatus.NOT_CONFIGURED)
                .orElseThrow(() -> new EmailDeliveryException(EmailDeliveryException.SENDER_NOT_CONFIGURED));
        deliver(config, message);
    }

    @Override
    public void sendTest(EmailMessage message) {
        TenantEmailSenderConfig config = configs.findByTenantId(message.tenantId())
                .orElseThrow(() -> new EmailDeliveryException(EmailDeliveryException.SENDER_NOT_CONFIGURED));
        deliver(config, message);
    }

    @Override
    public void invalidate(UUID tenantId) {
        cache.remove(tenantId);
    }

    private void deliver(TenantEmailSenderConfig config, EmailMessage message) {
        JavaMailSender sender = senderFor(config);
        try {
            sender.send(MailComposer.compose(sender, config.getSenderEmail(), config.getSenderName(), message));
        } catch (MailException ex) {
            // Se descarta el mensaje y la causa de la libreria: pueden traer detalles del servidor.
            throw EmailDeliveryException.from(ex);
        }
    }

    private JavaMailSender senderFor(TenantEmailSenderConfig config) {
        CachedSender cached = cache.get(config.getTenantId());
        if (cached != null
                && Objects.equals(cached.configUpdatedAt(), config.getUpdatedAt())
                && cached.senderEmail().equals(config.getSenderEmail())) {
            return cached.sender();
        }
        String password;
        try {
            password = crypto.decryptCredential(
                    new EmailCredentialCrypto.Encrypted(
                            config.getEncryptedCredential(), config.getCredentialIv(), config.getEncryptionKeyVersion()),
                    config.getTenantId());
        } catch (IllegalStateException ex) {
            throw new EmailDeliveryException(EmailDeliveryException.CREDENTIAL_UNREADABLE);
        }
        JavaMailSender created = senderFactory.create(config.getSenderEmail(), password);
        cache.put(config.getTenantId(), new CachedSender(config.getUpdatedAt(), config.getSenderEmail(), created));
        return created;
    }
}
