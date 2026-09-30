package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.ProductInventorySettingsResponse;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.inventory.service.InventorySettingsService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/settings")
@RequiredArgsConstructor
public class InventorySettingsController {

    private final InventorySettingsService inventorySettingsService;

    @GetMapping
    @RequirePermission("inventory.stock.read")
    public PageResponse<ProductInventorySettingsResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) UUID productId,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventorySettingsService.list(branchId, productId, pageable);
    }

    @GetMapping("/{productId}")
    @RequirePermission("inventory.stock.read")
    public ResponseEntity<ProductInventorySettingsResponse> get(
            @PathVariable UUID productId, @RequestParam UUID branchId) {
        return inventorySettingsService
                .get(branchId, productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/{productId}")
    @RequirePermission("catalog.products.update")
    public ProductInventorySettingsResponse upsert(
            @PathVariable UUID productId,
            @RequestParam UUID branchId,
            @Valid @RequestBody UpdateInventorySettingsRequest request) {
        return inventorySettingsService.upsert(branchId, productId, request);
    }
}
