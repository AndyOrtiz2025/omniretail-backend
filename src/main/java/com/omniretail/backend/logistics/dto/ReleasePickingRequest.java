package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReleasePickingRequest(
        @NotBlank @Size(max = 500) String reason) {}
