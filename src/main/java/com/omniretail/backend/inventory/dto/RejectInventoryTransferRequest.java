package com.omniretail.backend.inventory.dto;

import jakarta.validation.constraints.Size;

public record RejectInventoryTransferRequest(@Size(max = 500) String reviewNotes) {}
