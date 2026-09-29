package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.*;
import com.omniretail.backend.purchasing.service.PurchaseOrderService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/purchasing/orders") @RequiredArgsConstructor
public class PurchaseOrderController {
    private final PurchaseOrderService service;
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @RequirePermission("purchasing.orders.create")
    public PurchaseOrderResponse create(@Valid @RequestBody CreatePurchaseOrderRequest request) { return service.create(request); }
    @GetMapping @RequirePermission("purchasing.orders.read") public List<PurchaseOrderResponse> list() { return service.list(); }
    @GetMapping("/{id}") @RequirePermission("purchasing.orders.read") public PurchaseOrderResponse get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/{id}/submit") @RequirePermission("purchasing.orders.create") public PurchaseOrderResponse submit(@PathVariable UUID id) { return service.submit(id); }
    @PostMapping("/{id}/approve") @RequirePermission("purchasing.orders.approve") public PurchaseOrderResponse approve(@PathVariable UUID id) { return service.approve(id); }
}
