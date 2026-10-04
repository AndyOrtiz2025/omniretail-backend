package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.CrossBranchStockDto;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.dto.InventoryStockPageResponse;
import com.omniretail.backend.inventory.service.InventoryStockQueryService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/stock")
@RequiredArgsConstructor
public class InventoryStockController {

    private final InventoryStockQueryService stockQueryService;

    @GetMapping
    @RequirePermission("inventory.stock.read")
    public InventoryStockPageResponse list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) InventoryAlertStatus status,
            @Parameter(hidden = true) Pageable pageable) {
        return stockQueryService.list(branchId, search, categoryId, status, pageable);
    }

    @GetMapping("/branches")
    @RequirePermission("inventory.stock.read")
    public List<CrossBranchStockDto> listBranches(
            @RequestParam UUID productId, @RequestParam UUID branchId) {
        return stockQueryService.listBranches(productId, branchId);
    }
}
