package com.omniretail.backend.commercial;

import java.util.UUID;

/** Respuesta 202: la solicitud quedo registrada de forma duradera. No lleva datos personales. */
public record ContactRequestAccepted(UUID requestId, String status) {

    public static ContactRequestAccepted received(UUID requestId) {
        return new ContactRequestAccepted(requestId, "RECEIVED");
    }
}
