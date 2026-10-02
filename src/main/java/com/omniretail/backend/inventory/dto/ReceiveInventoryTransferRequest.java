package com.omniretail.backend.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ReceiveInventoryTransferRequest(
        @NotBlank @Size(max = 128) String confirmationId,
        @NotNull UUID destinationLocationId,
        @NotEmpty List<@Valid ReceiveInventoryTransferItemRequest> items) {}
