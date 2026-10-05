package com.omniretail.backend.shared.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * Correo a enviar. {@code html} es opcional (el texto plano siempre va). Nunca debe llevar contrasenas
 * ni otros secretos de la cuenta; el cuerpo puede llevar tokens de un solo uso, por eso no se persiste en claro.
 */
public record EmailMessage(UUID tenantId, EmailPurpose purpose, String to, String subject, String body, String html) {

    public EmailMessage {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(to, "to");
    }

    public static EmailMessage text(UUID tenantId, EmailPurpose purpose, String to, String subject, String body) {
        return new EmailMessage(tenantId, purpose, to, subject, body, null);
    }
}
