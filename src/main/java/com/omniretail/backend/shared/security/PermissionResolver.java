package com.omniretail.backend.shared.security;

import java.util.UUID;

/**
 * Resuelve los permisos del rol de un usuario. Lo implementa el modulo {@code administration}; se
 * define aqui para que {@code shared} no dependa de sus repositorios.
 */
public interface PermissionResolver {

    /** true si el rol existe, pertenece a {@code tenantId}, esta activo e incluye {@code permission}. */
    boolean hasPermission(UUID tenantId, UUID roleId, String permission);
}
