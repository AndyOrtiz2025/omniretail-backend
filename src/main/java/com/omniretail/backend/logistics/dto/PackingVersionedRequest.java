package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PackingVersionedRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 128) String operationId) {}
