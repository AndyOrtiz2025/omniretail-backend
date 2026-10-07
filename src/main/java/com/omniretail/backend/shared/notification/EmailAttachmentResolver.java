package com.omniretail.backend.shared.notification;

import java.util.UUID;

/**
 * Genera el contenido de un adjunto a partir de su {@link EmailAttachmentReference}. Cada módulo de negocio
 * registra su propia implementación como bean; {@link EmailDeliveryService} elige la que soporte el tipo.
 *
 * <p>Se invoca en cada intento de envío (también en los reintentos) y fuera de la transacción de negocio, por
 * lo que debe filtrar por {@code tenantId} de forma explícita y no depender del usuario autenticado. Si el
 * adjunto no puede generarse debe lanzar {@link EmailDeliveryException} con
 * {@link EmailDeliveryException#ATTACHMENT_UNAVAILABLE}: el correo no se envía sin su adjunto.
 */
public interface EmailAttachmentResolver {

    boolean supports(String type);

    EmailAttachmentContent resolve(UUID tenantId, EmailAttachmentReference reference);
}
