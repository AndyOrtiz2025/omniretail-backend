package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import java.util.UUID;

/** Detalle informativo de un proveedor: campos del listado más dirección y notas. */
public record PurchasingSupplierDetailResponse(
        UUID id,
        String name,
        String legalName,
        String taxId,
        String email,
        String phone,
        Integer leadTimeDays,
        SupplierStatus status,
        String address,
        String notes) {

    public static PurchasingSupplierDetailResponse from(Supplier supplier) {
        return new PurchasingSupplierDetailResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getLegalName(),
                supplier.getTaxId(),
                supplier.getEmail(),
                supplier.getPhone(),
                supplier.getLeadTimeDays(),
                supplier.getStatus(),
                supplier.getAddress(),
                supplier.getNotes());
    }
}
