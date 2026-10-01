package com.omniretail.backend.shared.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Contrato administrativo de empleados implementado por auth, sin exponer entidades del modulo. */
public interface EmployeeInvitationPort {

    /** Crea una invitacion o reemplaza la anterior de un empleado del tenant indicado. */
    EmployeeInviteResult inviteEmployee(UUID tenantId, UUID userId);

    /** Consulta los estados de autenticacion de empleados del tenant indicado. */
    List<EmployeeAuthSummary> getAuthSummaries(UUID tenantId, Collection<UUID> userIds);
}
