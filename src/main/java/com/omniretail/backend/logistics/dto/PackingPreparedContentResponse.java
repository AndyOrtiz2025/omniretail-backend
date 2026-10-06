package com.omniretail.backend.logistics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PackingPreparedContentResponse(
        UUID productId,
        String sku,
        String name,
        BigDecimal quantity,
        List<String> serialNumbers,
        List<PhysicalTraceSelectionResponse> trackingSelections) {

    public PackingPreparedContentResponse(
            UUID productId,
            String sku,
            String name,
            BigDecimal quantity,
            List<String> serialNumbers) {
        this(productId, sku, name, quantity, serialNumbers, List.of());
    }
}
