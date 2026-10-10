package com.omniretail.backend.logistics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cantidades logísticas en unidad física. Las cantidades empacada y despachada pueden ser
 * derivadas porque Order Packing/Dispatch no persisten snapshots independientes por línea.
 */
public record LogisticsHistoryLineResponse(
        UUID productId,
        String productName,
        BigDecimal requestedQuantity,
        BigDecimal pickedQuantity,
        BigDecimal packedQuantity,
        BigDecimal dispatchedQuantity,
        List<PhysicalTraceSelectionResponse> trackingSelections) {}
