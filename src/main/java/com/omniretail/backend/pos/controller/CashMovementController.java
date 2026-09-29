package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CashMovementResponse;
import com.omniretail.backend.pos.dto.CreateCashMovementRequest;
import com.omniretail.backend.pos.service.CashMovementService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pos/cash-movements")
@RequiredArgsConstructor
public class CashMovementController {
    private final CashMovementService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("pos.cash.movement.create")
    public CashMovementResponse create(@Valid @RequestBody CreateCashMovementRequest request) {
        return service.create(request);
    }

    @GetMapping("/shift/{cashShiftId}")
    @RequirePermission("pos.cash.read")
    public List<CashMovementResponse> list(@PathVariable java.util.UUID cashShiftId) {
        return service.list(new CreateCashMovementRequest(cashShiftId, null, null, ""));
    }
}
