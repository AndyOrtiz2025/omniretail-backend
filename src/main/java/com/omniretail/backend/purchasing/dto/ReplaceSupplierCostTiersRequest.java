package com.omniretail.backend.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReplaceSupplierCostTiersRequest(@NotNull List<@Valid SupplierCostTierRequest> tiers) {}
