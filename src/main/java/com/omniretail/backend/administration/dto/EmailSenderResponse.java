package com.omniretail.backend.administration.dto;

import com.omniretail.backend.shared.notification.EmailSenderProvider;
import com.omniretail.backend.shared.notification.EmailSenderStatus;
import com.omniretail.backend.shared.notification.TenantEmailSenderConfig;
import java.time.Instant;

/** Estado del remitente. Por contrato NUNCA incluye la contrasena de aplicacion ni nada derivado de ella. */
public record EmailSenderResponse(
        EmailSenderProvider provider,
        String senderEmail,
        String senderName,
        boolean configured,
        EmailSenderStatus status,
        Instant lastVerifiedAt,
        Instant lastFailureAt) {

    public static EmailSenderResponse notConfigured() {
        return new EmailSenderResponse(
                EmailSenderProvider.GMAIL_SMTP, null, null, false, EmailSenderStatus.NOT_CONFIGURED, null, null);
    }

    public static EmailSenderResponse from(TenantEmailSenderConfig config) {
        return new EmailSenderResponse(
                config.getProvider(),
                config.getSenderEmail(),
                config.getSenderName(),
                true,
                config.getStatus(),
                config.getLastVerifiedAt(),
                config.getLastFailureAt());
    }
}
