package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ValidateSerialsRequest(
        @NotNull UUID productId,
        @NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 100) String> serialNumbers) {}
