package com.omniretail.backend.shared.notification;

import java.util.UUID;

/** Remitente propio del tenant (su cuenta Gmail): correos operativos. */
public interface TenantEmailSender extends EmailSender {

    /** Como {@link #send} pero tambien con la cuenta en estado ERROR: es la forma de volver a verificarla. */
    void sendTest(EmailMessage message);

    /** Descarta el cliente SMTP cacheado del tenant (cambio o borrado de su configuracion). */
    void invalidate(UUID tenantId);
}
