package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.CustomerOrderDetailResponse;
import com.omniretail.backend.ecommerce.dto.CustomerOrderResponse;
import com.omniretail.backend.ecommerce.service.CustomerOrderService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Historial privado del cliente; el tenant y el customer se resuelven desde el JWT. */
@RestController
@RequestMapping("/customer/orders")
@RequiredArgsConstructor
@Tag(name = "Customer orders", description = "Historial de compras del cliente autenticado.")
public class CustomerOrderController {

    private final CustomerOrderService customerOrderService;

    @GetMapping
    @RequirePermission("customer.account.read")
    @Operation(summary = "List my orders", description = "Devuelve únicamente los pedidos e-commerce del cliente autenticado.")
    public PageResponse<CustomerOrderResponse> list(
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return customerOrderService.list(pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission("customer.account.read")
    @Operation(summary = "Get my order details", description = "Devuelve las líneas, dirección y pago del pedido del cliente autenticado.")
    public CustomerOrderDetailResponse getById(@PathVariable UUID id) {
        return customerOrderService.getById(id);
    }
}
