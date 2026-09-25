package com.omniretail.backend.shared.security;

import java.util.UUID;

/**
 * Revoca las sesiones activas de un usuario. Lo implementa el modulo {@code auth}; se define aqui
 * para que {@code shared}/{@code administration} no dependan de sus repositorios.
 */
public interface SessionRevoker {

    /** Marca como revocadas todas las sesiones activas (no vencidas ni ya revocadas) de {@code userId}. */
    void revokeAllSessions(UUID userId);
}
