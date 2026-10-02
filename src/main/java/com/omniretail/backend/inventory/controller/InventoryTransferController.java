package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferReceiptResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.service.InventoryTransferService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/transfers")
@RequiredArgsConstructor
public class InventoryTransferController {

    private final InventoryTransferService inventoryTransferService;

    @GetMapping
    @RequirePermission("inventory.transfers.manage")
    public PageResponse<InventoryTransferResponse> list(
            @RequestParam(required = false) UUID sourceBranchId,
            @RequestParam(required = false) UUID destinationBranchId,
            @RequestParam(required = false) InventoryTransferStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryTransferService.listTransfers(
                sourceBranchId, destinationBranchId, status, pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferResponse detail(@PathVariable UUID id) {
        return inventoryTransferService.getTransfer(id);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelInventoryTransferRequest request) {
        return inventoryTransferService.cancelTransfer(id, request);
    }

    @PostMapping("/{id}/receipts")
    @RequirePermission("inventory.transfers.manage")
    public InventoryTransferReceiptResponse receive(
            @PathVariable UUID id,
            @Valid @RequestBody ReceiveInventoryTransferRequest request) {
        return inventoryTransferService.receiveTransfer(id, request);
    }
}
