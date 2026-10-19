package com.omniretail.backend.commercial;

/** Estado de cada correo de una solicitud. SENT solo con confirmacion del transporte SMTP. */
public enum ContactMailStatus {
    PENDING,
    SENT,
    FAILED,
    NOT_REQUIRED
}
