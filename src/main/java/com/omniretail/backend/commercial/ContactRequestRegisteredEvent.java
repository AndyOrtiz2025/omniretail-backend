package com.omniretail.backend.commercial;

import java.util.UUID;

/** Se publica dentro de la transaccion de registro; el envio de correos ocurre solo tras el commit. */
public record ContactRequestRegisteredEvent(UUID requestId) {
}
