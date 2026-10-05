package com.omniretail.backend.shared.notification;

import org.springframework.mail.javamail.JavaMailSender;

/** Crea el cliente SMTP de un tenant. Costura para sustituir la conexion real en los tests. */
@FunctionalInterface
public interface TenantMailSenderFactory {

    JavaMailSender create(String username, String password);
}
