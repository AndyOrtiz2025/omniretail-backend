package com.omniretail.backend.purchasing.dto;

import com.omniretail.backend.purchasing.entity.SupplierCostTier;
import java.math.BigDecimal;
import java.util.UUID;

public record SupplierCostTierResponse(
        UUID id, UUID supplierProductId, BigDecimal minQuantity, BigDecimal unitCost) {

    public static SupplierCostTierResponse from(SupplierCostTier tier) {
        return new SupplierCostTierResponse(
                tier.getId(), tier.getSupplierProductId(), tier.getMinQuantity(), tier.getUnitCost());
    }
}
