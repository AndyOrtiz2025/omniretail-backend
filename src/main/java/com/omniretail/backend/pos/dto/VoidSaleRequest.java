package com.omniretail.backend.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoidSaleRequest(
        @NotBlank @Size(max = 1000) String reason) {}
