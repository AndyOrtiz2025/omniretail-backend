package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.SupplierStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSupplierRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 160) String legalName,
        @Size(max = 20) String taxId,
        @Email @Size(max = 254) String email,
        @Pattern(
                        regexp = "^$|^[0-9]{4}-[0-9]{4}$|^[0-9]{8}$",
                        message = "Formato de teléfono inválido (debe ser 0000-0000 u 8 dígitos)")
                @Size(max = 9)
                String phone,
        @Size(max = 180) String address,
        @Size(max = 500) String notes,
        SupplierStatus status) {
}
