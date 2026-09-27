package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import java.time.Instant;
import java.util.UUID;

public record SupplierResponse(
        UUID id,
        UUID tenantId,
        String name,
        String legalName,
        String taxId,
        String email,
        String phone,
        String address,
        String notes,
        Integer leadTimeDays,
        SupplierStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getTenantId(),
                supplier.getName(),
                supplier.getLegalName(),
                supplier.getTaxId(),
                supplier.getEmail(),
                supplier.getPhone(),
                supplier.getAddress(),
                supplier.getNotes(),
                supplier.getLeadTimeDays(),
                supplier.getStatus(),
                supplier.getCreatedAt(),
                supplier.getUpdatedAt());
    }
}
