package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse;
import com.omniretail.backend.administration.dto.UpdateSubscriptionAddonsRequest;
import com.omniretail.backend.administration.service.TenantSubscriptionService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/subscriptions")
@RequiredArgsConstructor
public class TenantSubscriptionController {

    private final TenantSubscriptionService subscriptionService;

    @GetMapping
    @RequirePermission("admin.plans.read")
    public TenantSubscriptionDetailsResponse getCurrent() {
        return subscriptionService.getCurrent();
    }

    @PutMapping("/addons")
    @RequirePermission("admin.plans.manage")
    public TenantSubscriptionDetailsResponse updateAddons(@Valid @RequestBody UpdateSubscriptionAddonsRequest request) {
        return subscriptionService.updateAddons(request);
    }
}
