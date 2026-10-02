package com.omniretail.backend.ecommerce.dto;

import com.omniretail.backend.administration.entity.Branch;
import java.util.UUID;

/** Datos de contacto de una sucursal que puede mostrarse en la tienda pública. */
public record PublicStorefrontBranchResponse(
        UUID id, String code, String name, String address, String phone, String email) {

    public static PublicStorefrontBranchResponse from(Branch branch) {
        return new PublicStorefrontBranchResponse(
                branch.getId(),
                branch.getCode(),
                branch.getName(),
                branch.getAddress(),
                branch.getPhone(),
                branch.getEmail());
    }
}
