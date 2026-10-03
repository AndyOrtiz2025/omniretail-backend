package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.ExpiringLotDto;
import com.omniretail.backend.inventory.dto.InventoryLotAvailabilityDto;
import com.omniretail.backend.inventory.dto.InventorySerialAvailabilityDto;
import com.omniretail.backend.inventory.service.InventoryTraceabilityQueryService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryTraceabilityController {

    private final InventoryTraceabilityQueryService queryService;

    @GetMapping("/lots")
    @RequirePermission("inventory.stock.read")
    public List<InventoryLotAvailabilityDto> availableLots(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId) {
        return queryService.availableLots(branchId, productId, locationId);
    }

    @GetMapping("/serials")
    @RequirePermission("inventory.stock.read")
    public List<InventorySerialAvailabilityDto> availableSerials(
            @RequestParam UUID branchId,
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID lotId) {
        return queryService.availableSerials(branchId, productId, locationId, lotId);
    }

    @GetMapping("/lots/expiring")
    @RequirePermission("inventory.stock.read")
    public PageResponse<ExpiringLotDto> expiringLots(
            @RequestParam UUID branchId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID locationId,
            @Parameter(hidden = true) Pageable pageable) {
        return queryService.expiringLots(branchId, days, productId, locationId, pageable);
    }
}
