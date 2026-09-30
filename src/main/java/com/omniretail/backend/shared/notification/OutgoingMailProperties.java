package com.omniretail.backend.shared.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Remitente de los correos salientes (propiedad {@code app.mail.from}). */
@ConfigurationProperties("app.mail")
public record OutgoingMailProperties(String from) {
}
