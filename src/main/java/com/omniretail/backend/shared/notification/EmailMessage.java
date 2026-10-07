package com.omniretail.backend.shared.notification;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Correo a enviar. {@code html} es opcional (el texto plano siempre va). Nunca debe llevar contrasenas
 * ni otros secretos de la cuenta; el cuerpo puede llevar tokens de un solo uso, por eso no se persiste en claro.
 *
 * <p>{@code attachments} son descriptores (se persisten con el payload cifrado y permiten reintentar);
 * {@code attachmentContents} es el contenido ya resuelto para UN intento de envío y jamás se persiste.
 */
public record EmailMessage(
        UUID tenantId,
        EmailPurpose purpose,
        String to,
        String subject,
        String body,
        String html,
        List<EmailAttachmentReference> attachments,
        List<EmailAttachmentContent> attachmentContents) {

    public EmailMessage {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(to, "to");
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        attachmentContents = attachmentContents == null ? List.of() : List.copyOf(attachmentContents);
    }

    /** Correo sin adjuntos (compatibilidad con todos los usos anteriores). */
    public EmailMessage(UUID tenantId, EmailPurpose purpose, String to, String subject, String body, String html) {
        this(tenantId, purpose, to, subject, body, html, List.of(), List.of());
    }

    public static EmailMessage text(UUID tenantId, EmailPurpose purpose, String to, String subject, String body) {
        return new EmailMessage(tenantId, purpose, to, subject, body, null);
    }

    /** Texto plano + HTML + adjuntos por referencia. */
    public static EmailMessage withAttachments(
            UUID tenantId,
            EmailPurpose purpose,
            String to,
            String subject,
            String body,
            String html,
            List<EmailAttachmentReference> attachments) {
        return new EmailMessage(tenantId, purpose, to, subject, body, html, attachments, List.of());
    }

    /** Copia con el contenido de los adjuntos ya resuelto, lista para componer el MimeMessage. */
    public EmailMessage withAttachmentContents(List<EmailAttachmentContent> contents) {
        return new EmailMessage(tenantId, purpose, to, subject, body, html, attachments, contents);
    }
}
