package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.administration.service.CashShiftAdminService;
import com.omniretail.backend.pos.entity.CashShiftStatus;
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
@RequestMapping("/administration/cash-shifts")
@RequiredArgsConstructor
public class CashShiftAdminController {

    private final CashShiftAdminService cashShiftAdminService;

    @RequirePermission("admin.cash.read")
    @GetMapping
    public List<CashShiftResponse> list(
            @RequestParam(required = false) CashShiftStatus status,
            @RequestParam(required = false) UUID branchId) {
        return cashShiftAdminService.listCashShifts(status, branchId);
    }

    @RequirePermission("admin.cash.read")
    @GetMapping("/{id}")
    public CashShiftResponse getById(@PathVariable UUID id) {
        return cashShiftAdminService.getCashShiftById(id);
    }
}
