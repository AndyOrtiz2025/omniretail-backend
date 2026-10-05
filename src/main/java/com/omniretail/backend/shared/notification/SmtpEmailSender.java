package com.omniretail.backend.shared.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Remitente de plataforma: el SMTP global (MAIL_*; Mailpit en desarrollo). */
@Component
@RequiredArgsConstructor
public class SmtpEmailSender implements PlatformEmailSender {

    private final JavaMailSender mailSender;
    private final OutgoingMailProperties mailProperties;

    @Override
    public void send(EmailMessage message) {
        try {
            mailSender.send(MailComposer.compose(mailSender, mailProperties.from(), null, message));
        } catch (MailException ex) {
            throw EmailDeliveryException.from(ex);
        }
    }
}
