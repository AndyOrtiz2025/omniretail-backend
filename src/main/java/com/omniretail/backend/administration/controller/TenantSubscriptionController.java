package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.AssignTenantSubscriptionRequest;
import com.omniretail.backend.administration.dto.TenantSubscriptionResponse;
import com.omniretail.backend.administration.service.TenantSubscriptionService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/subscriptions")
@RequirePermission("admin.subscriptions.manage")
@RequiredArgsConstructor
public class TenantSubscriptionController {

    private final TenantSubscriptionService subscriptionService;

    @GetMapping("/current")
    public TenantSubscriptionResponse getCurrent() {
        return subscriptionService.getCurrent();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantSubscriptionResponse assign(@Valid @RequestBody AssignTenantSubscriptionRequest request) {
        return subscriptionService.assign(request);
    }

    @PutMapping("/renew")
    public TenantSubscriptionResponse renew() {
        return subscriptionService.renew();
    }

    @PutMapping("/cancel-at-period-end")
    public TenantSubscriptionResponse cancelAtPeriodEnd() {
        return subscriptionService.cancelAtPeriodEnd();
    }
}
