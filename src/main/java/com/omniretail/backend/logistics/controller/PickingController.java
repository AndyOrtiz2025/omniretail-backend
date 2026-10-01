package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.CreatePickingIncidentRequest;
import com.omniretail.backend.logistics.dto.PickingActionResponse;
import com.omniretail.backend.logistics.dto.PickingDetailResponse;
import com.omniretail.backend.logistics.dto.PickingIncidentResponse;
import com.omniretail.backend.logistics.dto.PickingLineResponse;
import com.omniretail.backend.logistics.dto.PickingQueueResponse;
import com.omniretail.backend.logistics.dto.PickingReleaseResponse;
import com.omniretail.backend.logistics.dto.ReleasePickingRequest;
import com.omniretail.backend.logistics.dto.UpdatePickingItemRequest;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/logistics/picking")
@RequiredArgsConstructor
public class PickingController {

    private final PickingService pickingService;

    @GetMapping
    @RequirePermission("logistics.picking.read")
    public List<PickingQueueResponse> queue(@RequestParam UUID branchId) {
        return pickingService.getQueue(branchId);
    }

    @GetMapping("/{pickingOrderId}")
    @RequirePermission("logistics.picking.read")
    public PickingDetailResponse detail(
            @RequestParam UUID branchId, @PathVariable UUID pickingOrderId) {
        return pickingService.getDetail(branchId, pickingOrderId);
    }

    @PostMapping("/{pickingOrderId}/assign")
    @RequirePermission("logistics.picking.start")
    public PickingActionResponse assign(
            @RequestParam UUID branchId, @PathVariable UUID pickingOrderId) {
        return pickingService.assign(branchId, pickingOrderId);
    }

    @PostMapping("/{pickingOrderId}/release")
    @RequirePermission("logistics.picking.start")
    public PickingReleaseResponse release(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @Valid @RequestBody ReleasePickingRequest request) {
        return pickingService.release(branchId, pickingOrderId, request.reason());
    }

    @PatchMapping("/{pickingOrderId}/items/{pickingItemId}")
    @RequirePermission("logistics.picking.start")
    public PickingLineResponse updateItem(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @PathVariable UUID pickingItemId,
            @Valid @RequestBody UpdatePickingItemRequest request) {
        return pickingService.updateItem(branchId, pickingOrderId, pickingItemId, request);
    }

    @PostMapping("/{pickingOrderId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("logistics.picking.start")
    public PickingIncidentResponse createIncident(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @Valid @RequestBody CreatePickingIncidentRequest request) {
        return pickingService.createIncident(branchId, pickingOrderId, request);
    }

    @PatchMapping("/{pickingOrderId}/incidents/{incidentId}/resolve")
    @RequirePermission("logistics.picking.start")
    public PickingIncidentResponse resolveIncident(
            @RequestParam UUID branchId,
            @PathVariable UUID pickingOrderId,
            @PathVariable UUID incidentId) {
        return pickingService.resolveIncident(branchId, pickingOrderId, incidentId);
    }

    @PostMapping("/{pickingOrderId}/complete")
    @RequirePermission("logistics.picking.complete")
    public PickingActionResponse complete(
            @RequestParam UUID branchId, @PathVariable UUID pickingOrderId) {
        return pickingService.complete(branchId, pickingOrderId);
    }
}
