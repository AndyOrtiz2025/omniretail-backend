package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBranchRequest(
        @NotBlank @Size(max = 16) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull BranchType type,
        @Size(max = 180) String address,
        @Pattern(
                        regexp = "^$|^[0-9]{4}-[0-9]{4}$|^[0-9]{8}$",
                        message = "Formato de telefono invalido (debe ser 0000-0000 u 8 digitos)")
                @Size(max = 9)
                String phone,
        @Email @Size(max = 254) String email,
        BranchStatus status) {
}
