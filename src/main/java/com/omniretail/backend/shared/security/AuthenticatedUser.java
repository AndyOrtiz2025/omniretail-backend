package com.omniretail.backend.shared.security;

import com.omniretail.backend.administration.entity.UserType;
import java.util.UUID;

/**
 * Identidad del usuario autenticado, construida solo a partir de los claims del JWT. Es el
 * principal de la request: el {@code tenantId} de cualquier operacion sale de aqui, nunca del
 * body, query ni headers.
 *
 * @param roleId nulo si el usuario no tiene rol asignado (p. ej. clientes).
 * @param branchId nulo si el usuario no tiene sucursal asignada.
 */
public record AuthenticatedUser(
        UUID userId, UUID tenantId, UserType userType, UUID roleId, UUID branchId, UUID sessionId) {
}
