package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CancelPurchaseOrderRequest;
import com.omniretail.backend.purchasing.dto.CreatePurchaseOrderRequest;
import com.omniretail.backend.purchasing.dto.PurchaseOrderResponse;
import com.omniretail.backend.purchasing.dto.UpdatePurchaseOrderRequest;
import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
import com.omniretail.backend.purchasing.service.PurchaseOrderService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchasing/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public PageResponse<PurchaseOrderResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchaseOrderService.list(branchId, supplierId, status, pageable);
    }

    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return purchaseOrderService.get(id);
    }

    @RequirePermission("purchasing.orders.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderResponse create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return purchaseOrderService.create(request);
    }

    @RequirePermission("purchasing.orders.create")
    @PutMapping("/{id}")
    public PurchaseOrderResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        return purchaseOrderService.update(id, request);
    }

    @RequirePermission("purchasing.orders.create")
    @PostMapping("/{id}/submit")
    public PurchaseOrderResponse submit(@PathVariable UUID id) {
        return purchaseOrderService.submit(id);
    }

    @RequirePermission("purchasing.orders.approve")
    @PostMapping("/{id}/approve")
    public PurchaseOrderResponse approve(@PathVariable UUID id) {
        return purchaseOrderService.approve(id);
    }

    @PostMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return purchaseOrderService.cancel(id, request);
    }
}
