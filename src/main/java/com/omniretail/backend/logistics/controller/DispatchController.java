package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.ConfirmTransferDispatchRequest;
import com.omniretail.backend.logistics.dto.DispatchQueueResponse;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.logistics.service.DispatchService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/logistics/dispatch")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchService dispatchService;

    @GetMapping
    @RequirePermission("logistics.dispatch.read")
    public List<DispatchQueueResponse> queue(@RequestParam UUID branchId) {
        return dispatchService.getQueue(branchId);
    }

    @GetMapping("/{orderId}")
    @RequirePermission("logistics.dispatch.read")
    public DispatchResponse detail(
            @RequestParam UUID branchId, @PathVariable UUID orderId) {
        return dispatchService.getDetail(branchId, orderId);
    }

    @PostMapping("/{orderId}/confirm")
    @RequirePermission("logistics.dispatch.confirm")
    public DispatchResponse confirm(
            @RequestParam UUID branchId,
            @PathVariable UUID orderId,
            @Valid @RequestBody ConfirmDispatchRequest request) {
        return dispatchService.confirm(branchId, orderId, request);
    }

    @GetMapping("/transfers/{transferId}")
    @RequirePermission("logistics.dispatch.read")
    public DispatchResponse transferDetail(
            @RequestParam UUID branchId, @PathVariable UUID transferId) {
        return dispatchService.getTransferDetail(branchId, transferId);
    }

    @PostMapping("/transfers/{transferId}/confirm")
    @RequirePermission("logistics.dispatch.confirm")
    public DispatchResponse confirmTransfer(
            @RequestParam UUID branchId,
            @PathVariable UUID transferId,
            @Valid @RequestBody ConfirmTransferDispatchRequest request) {
        return dispatchService.confirmTransfer(branchId, transferId, request);
    }
}
