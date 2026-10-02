package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.service.InventoryTransferService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/transfer-requests")
@RequiredArgsConstructor
public class InventoryTransferRequestController {

    private final InventoryTransferService inventoryTransferService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferRequestResponse create(
            @Valid @RequestBody CreateInventoryTransferRequest request) {
        return inventoryTransferService.createRequest(request);
    }

    @GetMapping
    @RequirePermission("inventory.transfers.manage")
    public PageResponse<InventoryTransferRequestResponse> list(
            @RequestParam(required = false) UUID requestingBranchId,
            @RequestParam(required = false) UUID sourceBranchId,
            @RequestParam(required = false) InventoryTransferRequestStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryTransferService.listRequests(
                requestingBranchId, sourceBranchId, status, pageable);
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferResponse approve(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveInventoryTransferRequest request) {
        return inventoryTransferService.approve(id, request);
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferRequestResponse reject(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RejectInventoryTransferRequest request) {
        return inventoryTransferService.reject(id, request);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferRequestResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelInventoryTransferRequest request) {
        return inventoryTransferService.cancelRequest(id, request);
    }
}
