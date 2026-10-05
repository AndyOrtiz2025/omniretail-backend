package com.omniretail.backend.shared.notification;

import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** smtp.gmail.com:587 con STARTTLS obligatorio y timeouts de conexion, lectura y escritura. */
@Component
@RequiredArgsConstructor
public class GmailMailSenderFactory implements TenantMailSenderFactory {

    private final GmailSmtpProperties properties;

    @Override
    public JavaMailSender create(String username, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties mail = sender.getJavaMailProperties();
        mail.put("mail.transport.protocol", "smtp");
        mail.put("mail.smtp.auth", "true");
        mail.put("mail.smtp.starttls.enable", "true");
        mail.put("mail.smtp.starttls.required", "true");
        String timeout = String.valueOf(properties.timeoutMillis());
        mail.put("mail.smtp.connectiontimeout", timeout);
        mail.put("mail.smtp.timeout", timeout);
        mail.put("mail.smtp.writetimeout", timeout);
        return sender;
    }
}
