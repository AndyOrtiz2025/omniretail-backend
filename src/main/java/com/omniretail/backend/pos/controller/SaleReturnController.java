package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.SaleReturnResponse;
import com.omniretail.backend.pos.service.SaleReturnService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class SaleReturnController {

    private final SaleReturnService service;

    @PostMapping("/sales/{saleId}/returns")
    @RequirePermission("pos.returns.create")
    public SaleReturnResponse create(
            @PathVariable UUID saleId, @Valid @RequestBody CreateSaleReturnRequest request) {
        return service.create(saleId, request);
    }

    @GetMapping("/returns")
    @RequirePermission("pos.returns.read")
    public Page<SaleReturnResponse> list(
            @RequestParam UUID branchId, Pageable pageable) {
        return service.list(branchId, pageable);
    }
}
