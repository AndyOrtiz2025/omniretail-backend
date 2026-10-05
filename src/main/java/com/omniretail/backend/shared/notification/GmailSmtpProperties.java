package com.omniretail.backend.shared.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Conexion al SMTP de Gmail ({@code app.mail.gmail.*}). Los valores por defecto son los de produccion. */
@ConfigurationProperties("app.mail.gmail")
public record GmailSmtpProperties(String host, Integer port, Integer timeoutMillis) {

    public GmailSmtpProperties {
        host = host == null || host.isBlank() ? "smtp.gmail.com" : host;
        port = port == null ? 587 : port;
        timeoutMillis = timeoutMillis == null ? 10_000 : timeoutMillis;
    }
}
