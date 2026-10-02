package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApproveInventoryTransferRequest(
        @NotBlank @Size(max = 128) String operationId,
        @Size(max = 500) String reviewNotes) {}
