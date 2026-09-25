package com.omniretail.backend.shared.security;

import java.util.UUID;

/**
 * Verifica en cada request autenticado que la sesion del token siga viva. Lo implementa el modulo
 * {@code auth}; se define aqui para que {@code shared} no dependa de sus repositorios.
 */
public interface SessionValidator {

    /** true si la sesion existe, pertenece a {@code userId}, no esta revocada y no ha vencido. */
    boolean isActive(UUID sessionId, UUID userId);
}
