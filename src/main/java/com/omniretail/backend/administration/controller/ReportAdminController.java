package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.ReportsDataResponse;
import com.omniretail.backend.administration.service.ReportAdminService;
import com.omniretail.backend.shared.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/reports")
@RequiredArgsConstructor
public class ReportAdminController {

    private final ReportAdminService reportAdminService;

    @RequirePermission("admin.reports.read")
    @GetMapping
    public ReportsDataResponse getReportsData() {
        return reportAdminService.getReportsData();
    }
}
