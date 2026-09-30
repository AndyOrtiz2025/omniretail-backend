package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CashShiftSummaryResponse;
import com.omniretail.backend.pos.service.CashShiftSummaryService;
import com.omniretail.backend.shared.security.RequirePermission;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pos/cash-shifts")
@RequiredArgsConstructor
public class CashShiftSummaryController {

    private final CashShiftSummaryService cashShiftSummaryService;

    @GetMapping("/{cashShiftId}/summary")
    @RequirePermission("pos.cash.read")
    public CashShiftSummaryResponse summary(@PathVariable UUID cashShiftId) {
        return cashShiftSummaryService.getSummary(cashShiftId);
    }
}
