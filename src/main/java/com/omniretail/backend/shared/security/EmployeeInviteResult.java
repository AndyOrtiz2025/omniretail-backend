package com.omniretail.backend.shared.security;

import java.time.Instant;
import java.util.UUID;

/** El token claro solo se entrega al administrador y por correo; nunca se persiste ni registra. */
public record EmployeeInviteResult(UUID userId, String invitationToken, Instant expiresAt) {
}
