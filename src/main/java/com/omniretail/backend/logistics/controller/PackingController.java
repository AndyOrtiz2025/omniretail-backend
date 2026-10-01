package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.PackingActionResponse;
import com.omniretail.backend.logistics.dto.PackingDetailResponse;
import com.omniretail.backend.logistics.dto.PackingFinalizeResponse;
import com.omniretail.backend.logistics.dto.PackingQueueResponse;
import com.omniretail.backend.logistics.dto.PackingVersionedRequest;
import com.omniretail.backend.logistics.dto.RegisterPackingLabelPrintRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.logistics.service.PackingService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/logistics/packing")
@RequiredArgsConstructor
public class PackingController {

    private final PackingService packingService;

    @GetMapping
    @RequirePermission("logistics.packing.read")
    public List<PackingQueueResponse> queue(@RequestParam UUID branchId) {
        return packingService.getQueue(branchId);
    }

    @GetMapping("/{packingId}")
    @RequirePermission("logistics.packing.read")
    public PackingDetailResponse detail(
            @RequestParam UUID branchId, @PathVariable UUID packingId) {
        return packingService.getDetail(branchId, packingId);
    }

    @PatchMapping("/{packingId}/preparation")
    @RequirePermission("logistics.packing.prepare")
    public PackingActionResponse savePreparation(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody SavePackingPreparationRequest request) {
        return packingService.savePreparation(branchId, packingId, request);
    }

    @PostMapping("/{packingId}/label")
    @RequirePermission("logistics.packing.prepare")
    public PackingActionResponse generateLabel(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody PackingVersionedRequest request) {
        return packingService.generateLabel(branchId, packingId, request);
    }

    @PostMapping("/{packingId}/label/print")
    @RequirePermission("logistics.packing.prepare")
    public PackingActionResponse registerLabelPrint(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody RegisterPackingLabelPrintRequest request) {
        return packingService.registerLabelPrint(branchId, packingId, request);
    }

    @PostMapping("/{packingId}/finalize")
    @RequirePermission("logistics.packing.finalize")
    public PackingFinalizeResponse finalizePacking(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody PackingVersionedRequest request) {
        return packingService.finalizePacking(branchId, packingId, request);
    }
}
