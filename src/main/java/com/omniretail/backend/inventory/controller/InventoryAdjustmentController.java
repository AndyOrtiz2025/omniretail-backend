package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.service.InventoryAdjustmentService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/adjustments")
@RequiredArgsConstructor
public class InventoryAdjustmentController {

    private final InventoryAdjustmentService inventoryAdjustmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("inventory.adjustment.create")
    public InventoryMovementResponse adjust(
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        return inventoryAdjustmentService.adjust(request);
    }
}
