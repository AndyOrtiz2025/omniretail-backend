package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.administration.dto.UpdateOrderStatusRequest;
import com.omniretail.backend.administration.service.OrderAdminService;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderAdminService orderAdminService;

    @GetMapping
    @RequirePermission("admin.orders.read")
    public List<OrderAdminResponse> list(@RequestParam(required = false) OrderStatus status) {
        return orderAdminService.list(status);
    }

    @GetMapping("/{id}")
    @RequirePermission("admin.orders.read")
    public OrderAdminResponse get(@PathVariable UUID id) {
        return orderAdminService.get(id);
    }

    @PatchMapping("/{id}/status")
    @RequirePermission("admin.orders.manage")
    public OrderAdminResponse updateStatus(@PathVariable UUID id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderAdminService.updateStatus(id, request.status());
    }
}
