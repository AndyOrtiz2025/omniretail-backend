package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.DashboardSummaryResponse;
import com.omniretail.backend.administration.service.DashboardAdminService;
import com.omniretail.backend.shared.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/dashboard")
@RequiredArgsConstructor
public class DashboardAdminController {

    private final DashboardAdminService dashboardAdminService;

    @RequirePermission("admin.dashboard.read")
    @GetMapping
    public DashboardSummaryResponse getDashboardSummary() {
        return dashboardAdminService.getDashboardSummary();
    }
}
