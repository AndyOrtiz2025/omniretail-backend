package com.omniretail.backend.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmTransferDispatchRequest(
        @NotBlank @Size(max = 128) String operationId) {}
