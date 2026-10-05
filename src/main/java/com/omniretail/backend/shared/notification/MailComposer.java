package com.omniretail.backend.shared.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Arma el MimeMessage comun a los dos canales (texto plano, y HTML como alternativa si viene). */
final class MailComposer {

    private MailComposer() {
    }

    static MimeMessage compose(JavaMailSender sender, String fromAddress, String fromName, EmailMessage message) {
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, message.html() != null, "UTF-8");
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
            return mime;
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new EmailDeliveryException(EmailDeliveryException.TRANSPORT_ERROR);
        }
    }
}
