package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.StorePickupHandoverResponse;
import com.omniretail.backend.logistics.service.StorePickupService;
import com.omniretail.backend.shared.security.RequirePermission;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/logistics/pickups")
@RequiredArgsConstructor
public class StorePickupController {

    private final StorePickupService storePickupService;

    @PostMapping("/{orderId}/handover")
    @RequirePermission("logistics.dispatch.confirm")
    public StorePickupHandoverResponse handover(
            @RequestParam UUID branchId, @PathVariable UUID orderId) {
        return storePickupService.handover(branchId, orderId);
    }
}
