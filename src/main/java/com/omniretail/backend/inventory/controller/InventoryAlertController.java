package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryAlertResponse;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.service.InventoryAlertService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/alerts")
@RequiredArgsConstructor
public class InventoryAlertController {

    private final InventoryAlertService inventoryAlertService;

    @GetMapping
    @RequirePermission("inventory.stock.read")
    public PageResponse<InventoryAlertResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) InventoryAlertStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryAlertService.list(branchId, status, pageable);
    }
}
