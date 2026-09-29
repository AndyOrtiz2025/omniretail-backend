package com.omniretail.backend.pos.controller;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.service.SaleService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/pos/sales") @RequiredArgsConstructor
public class SaleController {
    private final SaleService service;
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @RequirePermission("pos.sales.create")
    public SaleResponse create(@Valid @RequestBody CreateSaleRequest request) { return service.create(request); }
}
