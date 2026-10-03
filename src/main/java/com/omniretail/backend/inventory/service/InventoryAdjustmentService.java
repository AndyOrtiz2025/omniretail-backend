package com.omniretail.backend.inventory.service;

import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryAdjustmentService {

    private final InventoryTraceabilityAdjustmentService traceabilityAdjustmentService;

    public InventoryMovementResponse adjust(InventoryAdjustmentRequest request) {
        return traceabilityAdjustmentService.adjust(request);
    }
}
