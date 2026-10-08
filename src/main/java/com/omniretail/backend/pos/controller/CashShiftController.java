package com.omniretail.backend.pos.controller;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.pos.dto.CloseCashShiftRequest;
import com.omniretail.backend.pos.dto.OpenCashShiftRequest;
import com.omniretail.backend.pos.service.CashShiftService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pos/cash-shifts")
@RequiredArgsConstructor
public class CashShiftController {

    private final CashShiftService cashShiftService;

    @GetMapping("/open")
    @RequirePermission("pos.cash.read")
    public ResponseEntity<CashShiftResponse> openShift(@RequestParam java.util.UUID branchId) {
        return cashShiftService.findOpen(branchId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("pos.cash.open")
    public CashShiftResponse open(@Valid @RequestBody OpenCashShiftRequest request) {
        return cashShiftService.open(request);
    }

    @PostMapping("/close")
    @RequirePermission("pos.cash.close")
    public CashShiftResponse close(@Valid @RequestBody CloseCashShiftRequest request) {
        return cashShiftService.close(request);
    }
}
