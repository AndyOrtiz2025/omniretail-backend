package com.omniretail.backend.shared.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * Descriptor pequeño de un adjunto: se persiste en el payload cifrado del correo en lugar de los bytes. El
 * contenido se genera justo antes de cada intento de envío mediante el {@link EmailAttachmentResolver} que
 * soporte el {@code type}, de modo que {@code shared.notification} no conoce a los módulos de negocio.
 *
 * @param type identificador estable del tipo de adjunto (p. ej. {@code PURCHASE_ORDER_PDF})
 * @param resourceId recurso (del tenant del correo) a partir del cual se genera el adjunto
 * @param filename nombre sugerido; se sanea al componer el mensaje
 * @param mediaType tipo MIME del contenido (p. ej. {@code application/pdf})
 */
public record EmailAttachmentReference(String type, UUID resourceId, String filename, String mediaType) {

    public EmailAttachmentReference {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(mediaType, "mediaType");
    }
}
