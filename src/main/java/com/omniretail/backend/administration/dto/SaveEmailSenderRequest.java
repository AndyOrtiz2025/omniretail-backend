package com.omniretail.backend.administration.dto;

/**
 * Se valida en {@code EmailSenderConfigService} (codigo EMAIL_SENDER_INVALID). {@code appPassword} es
 * write-only: obligatoria al crear o al cambiar {@code senderEmail}; opcional si solo cambia el nombre.
 */
public record SaveEmailSenderRequest(String provider, String senderEmail, String senderName, String appPassword) {

    /** Un record imprimiria la contrasena en su toString. */
    @Override
    public String toString() {
        return "SaveEmailSenderRequest[provider=" + provider + ", senderEmail=" + senderEmail + ", appPassword=***]";
    }
}
