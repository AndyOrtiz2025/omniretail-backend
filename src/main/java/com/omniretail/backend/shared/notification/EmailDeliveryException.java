package com.omniretail.backend.shared.notification;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;

/**
 * Fallo de envio con un codigo saneado. Nunca lleva el mensaje ni la causa de javax/jakarta mail: pueden
 * contener respuestas del servidor SMTP y no deben llegar al log, a la BD ni al cliente.
 */
public class EmailDeliveryException extends RuntimeException {

    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    public static final String TRANSPORT_ERROR = "TRANSPORT_ERROR";
    public static final String SENDER_NOT_CONFIGURED = "SENDER_NOT_CONFIGURED";
    public static final String CREDENTIAL_UNREADABLE = "CREDENTIAL_UNREADABLE";

    private final String code;

    public EmailDeliveryException(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static EmailDeliveryException from(MailException ex) {
        return new EmailDeliveryException(
                ex instanceof MailAuthenticationException ? AUTHENTICATION_FAILED : TRANSPORT_ERROR);
    }
}
