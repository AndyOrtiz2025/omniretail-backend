package com.omniretail.backend.commercial;

/** RECEIVED: solicitud normal (se avisa al equipo y se confirma al solicitante). SPAM: honeypot, sin correos. */
public enum ContactRequestStatus {
    RECEIVED,
    SPAM
}
