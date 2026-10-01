package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.CustomerOrderResponse;
import com.omniretail.backend.ecommerce.service.CustomerOrderService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Historial privado del cliente; el tenant y el customer se resuelven desde el JWT. */
@RestController
@RequestMapping("/customer/orders")
@RequiredArgsConstructor
@Tag(name = "Pedidos del cliente", description = "Historial de compras del cliente autenticado.")
public class CustomerOrderController {

    private final CustomerOrderService customerOrderService;

    @GetMapping
    @RequirePermission("customer.account.read")
    @Operation(summary = "Consultar mis pedidos", description = "Devuelve únicamente los pedidos e-commerce del cliente autenticado.")
    public PageResponse<CustomerOrderResponse> list(
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return customerOrderService.list(pageable);
    }
}
