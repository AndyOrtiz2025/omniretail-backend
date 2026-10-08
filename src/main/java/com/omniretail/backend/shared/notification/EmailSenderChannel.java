package com.omniretail.backend.shared.notification;

/** Remitente con que salio (o se intento) un correo: el de plataforma (MAIL_*) o el Gmail del negocio. */
public enum EmailSenderChannel {
    PLATFORM,
    TENANT
}
