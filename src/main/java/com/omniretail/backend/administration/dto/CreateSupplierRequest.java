package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.shared.validation.GuatemalaPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupplierRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 160) String legalName,
        @Size(max = 20) String taxId,
        @Email @Size(max = 254) String email,
        @GuatemalaPhone @Size(max = 14) String phone,
        @Size(max = 180) String address,
        @Size(max = 500) String notes,
        SupplierStatus status) {
}
