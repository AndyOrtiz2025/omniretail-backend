package com.omniretail.backend.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CrossBranchStockDto(
        UUID branchId, String branchName, BigDecimal availableQuantity) {}
