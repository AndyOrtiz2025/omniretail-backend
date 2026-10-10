package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryCountResultResponse;
import com.omniretail.backend.inventory.dto.InventoryCountSnapshotResponse;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest;
import com.omniretail.backend.inventory.service.InventoryCountService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/counts")
@RequiredArgsConstructor
public class InventoryCountController {

    private final InventoryCountService countService;

    @GetMapping("/snapshot")
    @RequirePermission("inventory.stock.read")
    public InventoryCountSnapshotResponse snapshot(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId) {
        return countService.snapshot(branchId, productId, locationId);
    }

    @PostMapping("/reconcile")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("inventory.adjustment.create")
    public InventoryCountResultResponse reconcile(
            @Valid @RequestBody ReconcileInventoryCountRequest request) {
        return countService.reconcile(request);
    }
}
