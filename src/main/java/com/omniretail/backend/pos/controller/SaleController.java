package com.omniretail.backend.pos.controller;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.dto.SaleDetailResponse;
import com.omniretail.backend.pos.entity.SaleStatus;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    @GetMapping @RequirePermission("pos.sales.read")
    public Page<SaleResponse> list(@RequestParam UUID branchId, @RequestParam(required = false) SaleStatus status,
                                   @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                   Pageable pageable) {
        return service.list(branchId, status, from, to, pageable);
    }
    @GetMapping("/{id}") @RequirePermission("pos.sales.read")
    public SaleDetailResponse get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/{id}/void") @RequirePermission("pos.sales.void")
    public SaleResponse voidSale(@PathVariable UUID id) { return service.voidSale(id); }
}
