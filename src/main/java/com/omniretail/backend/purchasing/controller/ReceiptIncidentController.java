package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CreateReceiptIncidentRequest;
import com.omniretail.backend.purchasing.dto.ReceiptIncidentResponse;
import com.omniretail.backend.purchasing.dto.ResolveReceiptIncidentWithReplacementRequest;
import com.omniretail.backend.purchasing.service.ReceiptIncidentService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchasing/receipts")
@RequiredArgsConstructor
public class ReceiptIncidentController {

    private final ReceiptIncidentService receiptIncidentService;

    @GetMapping("/{receiptId}/incidents")
    public PageResponse<ReceiptIncidentResponse> list(
            @PathVariable UUID receiptId,
            @PageableDefault(size = 20) Pageable pageable) {
        return receiptIncidentService.list(receiptId, pageable);
    }

    @PostMapping("/{receiptId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("receiving.incidents.manage")
    public ReceiptIncidentResponse create(
            @PathVariable UUID receiptId,
            @Valid @RequestBody CreateReceiptIncidentRequest request) {
        return receiptIncidentService.create(receiptId, request);
    }

    @PostMapping("/incidents/{incidentId}/resolve-with-replacement")
    @RequirePermission("receiving.incidents.manage")
    public ReceiptIncidentResponse resolveWithReplacement(
            @PathVariable UUID incidentId,
            @Valid @RequestBody ResolveReceiptIncidentWithReplacementRequest request) {
        return receiptIncidentService.resolveWithReplacement(incidentId, request);
    }

    @PatchMapping("/incidents/{incidentId}/resolve")
    @RequirePermission("receiving.incidents.manage")
    public ReceiptIncidentResponse resolve(@PathVariable UUID incidentId) {
        return receiptIncidentService.resolve(incidentId);
    }
}
