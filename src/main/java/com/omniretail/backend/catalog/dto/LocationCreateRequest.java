package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record LocationCreateRequest(
        @NotNull UUID branchId,
        UUID parentId,
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull LocationType type,
        LocationStatus status) {}
