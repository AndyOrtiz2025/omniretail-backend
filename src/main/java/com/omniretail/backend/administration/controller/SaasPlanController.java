package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.SaasPlanResponse;
import com.omniretail.backend.administration.service.SaasPlanService;
import com.omniretail.backend.shared.security.RequirePermission;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/plans")
@RequiredArgsConstructor
public class SaasPlanController {

    private final SaasPlanService planService;

    @RequirePermission("admin.plans.read")
    @GetMapping
    public List<SaasPlanResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return planService.list(activeOnly);
    }

    @RequirePermission("admin.plans.read")
    @GetMapping("/{id}")
    public SaasPlanResponse get(@PathVariable UUID id) {
        return planService.get(id);
    }

}
