package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.SaleReturnEligibilityResponse;
import com.omniretail.backend.pos.dto.SaleReturnOperationResponse;
import com.omniretail.backend.pos.dto.SaleReturnResponse;
import com.omniretail.backend.pos.service.SaleReturnService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<?> create(
            @PathVariable UUID saleId,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody CreateSaleReturnRequest request) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(service.create(saleId, request));
        }
        SaleReturnOperationResponse response = service.create(saleId, idempotencyKey, request);
        return response.idempotent()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }

    @GetMapping("/sales/returns/eligibility")
    @RequirePermission("pos.returns.read")
    public SaleReturnEligibilityResponse eligibility(
            @RequestParam UUID branchId,
            @RequestParam String documentNumber) {
        return service.eligibility(branchId, documentNumber);
    }

    @GetMapping("/returns")
    @RequirePermission("pos.returns.read")
    public Page<SaleReturnResponse> list(
            @RequestParam UUID branchId, Pageable pageable) {
        return service.list(branchId, pageable);
    }
}
