package com.omniretail.backend.shared.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Arma el MimeMessage comun a los dos canales (texto plano, y HTML como alternativa si viene). */
final class MailComposer {

    private MailComposer() {
    }

    /** Solo letras, dígitos, punto, guion y guion bajo; evita separadores de ruta y saltos de línea en cabeceras. */
    static String safeFilename(String filename) {
        String cleaned = filename == null ? "" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        cleaned = cleaned.replaceAll("^\\.+", "");
        return cleaned.isBlank() ? "adjunto" : cleaned;
    }

    static MimeMessage compose(JavaMailSender sender, String fromAddress, String fromName, EmailMessage message) {
        try {
            MimeMessage mime = sender.createMimeMessage();
            boolean multipart = message.html() != null || !message.attachmentContents().isEmpty();
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, "UTF-8");
            if (fromName != null && !fromName.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            } else {
                helper.setFrom(fromAddress);
            }
            helper.setTo(message.to());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            String body = message.body() == null ? "" : message.body();
            if (message.html() != null) {
                helper.setText(body, message.html());
            } else {
                helper.setText(body);
            }
            for (EmailAttachmentContent attachment : message.attachmentContents()) {
                helper.addAttachment(
                        safeFilename(attachment.filename()),
                        new ByteArrayResource(attachment.bytes()),
                        attachment.mediaType());
            }
            return mime;
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new EmailDeliveryException(EmailDeliveryException.TRANSPORT_ERROR);
        }
    }
}
