package com.omniretail.backend.auth.repository;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import java.util.UUID;

/** Sesion con el tipo y el tenant de su usuario, leidos en una sola consulta (se valida en cada request). */
public record SessionWithOwner(Session session, UserType userType, UUID tenantId) {
}
