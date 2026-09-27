package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CustomerAdminResponse;
import com.omniretail.backend.administration.service.CustomerAdminService;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.shared.security.RequirePermission;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/customers")
@RequiredArgsConstructor
public class CustomerAdminController {

    private final CustomerAdminService customerAdminService;

    @RequirePermission("admin.customers.read")
    @GetMapping
    public List<CustomerAdminResponse> list(
            @RequestParam(required = false) CustomerStatus status) {
        return customerAdminService.listCustomers(status);
    }

    @RequirePermission("admin.customers.read")
    @GetMapping("/{id}")
    public CustomerAdminResponse getById(@PathVariable UUID id) {
        return customerAdminService.getCustomerById(id);
    }
}
