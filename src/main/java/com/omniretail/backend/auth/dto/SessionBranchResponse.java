package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import java.util.UUID;

/**
 * Sucursal que el empleado puede elegir en el selector de la sesion. A proposito sin direccion, telefono ni
 * correo: esta lectura no exige {@code admin.branches.read}, asi que solo lleva lo necesario para el selector.
 */
public record SessionBranchResponse(UUID id, String code, String name, BranchType type, BranchStatus status) {

    public static SessionBranchResponse from(Branch branch) {
        return new SessionBranchResponse(
                branch.getId(), branch.getCode(), branch.getName(), branch.getType(), branch.getStatus());
    }
}
