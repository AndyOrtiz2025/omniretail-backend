package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import java.util.UUID;

public record PurchasingSupplierResponse(
        UUID id, String name, Integer leadTimeDays, SupplierStatus status) {

    public static PurchasingSupplierResponse from(Supplier supplier) {
        return new PurchasingSupplierResponse(
                supplier.getId(), supplier.getName(), supplier.getLeadTimeDays(), supplier.getStatus());
    }
}
