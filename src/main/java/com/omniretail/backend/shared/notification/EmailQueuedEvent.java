package com.omniretail.backend.shared.notification;

import java.util.UUID;

/** Interno: el envio ya esta registrado ({@code email_delivery}) y falta hacerlo tras el commit. */
record EmailQueuedEvent(UUID deliveryId, EmailMessage message) {
}
