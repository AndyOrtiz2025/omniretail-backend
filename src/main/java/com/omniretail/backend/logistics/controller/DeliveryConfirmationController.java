package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.DeliveryConfirmationResponse;
import com.omniretail.backend.logistics.service.DeliveryConfirmationService;
import com.omniretail.backend.shared.security.RequirePermission;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/logistics/deliveries")
@RequiredArgsConstructor
public class DeliveryConfirmationController {

    private final DeliveryConfirmationService deliveryConfirmationService;

    @PostMapping("/{orderId}/confirm")
    @RequirePermission("logistics.dispatch.confirm")
    public DeliveryConfirmationResponse confirm(
            @RequestParam UUID branchId, @PathVariable UUID orderId) {
        return deliveryConfirmationService.confirm(branchId, orderId);
    }
}
