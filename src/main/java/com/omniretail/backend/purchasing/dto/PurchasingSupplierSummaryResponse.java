package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import java.util.UUID;

/** Fila del listado informativo de proveedores de Compras. */
public record PurchasingSupplierSummaryResponse(
        UUID id,
        String name,
        String legalName,
        String taxId,
        String email,
        String phone,
        Integer leadTimeDays,
        SupplierStatus status) {

    public static PurchasingSupplierSummaryResponse from(Supplier supplier) {
        return new PurchasingSupplierSummaryResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getLegalName(),
                supplier.getTaxId(),
                supplier.getEmail(),
                supplier.getPhone(),
                supplier.getLeadTimeDays(),
                supplier.getStatus());
    }
}
