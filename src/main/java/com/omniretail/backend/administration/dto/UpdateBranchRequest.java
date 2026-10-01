package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.shared.validation.GuatemalaPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateBranchRequest(
        @Size(max = 16) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull BranchType type,
        @Size(max = 180) String address,
        @GuatemalaPhone @Size(max = 14) String phone,
        @Email @Size(max = 254) String email,
        BranchStatus status) {
}
