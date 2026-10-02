package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.InventoryMovementDisplayType;
import com.omniretail.backend.inventory.dto.InventoryMovementPageResponse;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.service.InventoryService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/movements")
@RequiredArgsConstructor
public class InventoryMovementController {

    private final InventoryService inventoryService;

    @GetMapping
    @RequirePermission("inventory.movements.read")
    public InventoryMovementPageResponse search(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) InventoryMovementType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String displayType,
            @Parameter(hidden = true)
                    @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return inventoryService.searchMovements(
                branchId,
                productId,
                type,
                from,
                to,
                search,
                InventoryMovementDisplayType.parse(displayType),
                pageable);
    }
}
