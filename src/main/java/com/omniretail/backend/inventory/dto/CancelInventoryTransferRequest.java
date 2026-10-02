package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.Size;

public record CancelInventoryTransferRequest(@Size(max = 500) String reason) {}
