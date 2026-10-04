package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierDetailResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierResponse;
import com.omniretail.backend.purchasing.dto.PurchasingSupplierSummaryResponse;
import com.omniretail.backend.purchasing.service.PurchasingSupplierService;
import com.omniretail.backend.shared.dto.PageResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchasing/suppliers")
@RequiredArgsConstructor
public class PurchasingSupplierController {

    private final PurchasingSupplierService purchasingSupplierService;

    @GetMapping("/active")
    public List<PurchasingSupplierResponse> listActive() {
        return purchasingSupplierService.listActive();
    }

    @GetMapping
    public PageResponse<PurchasingSupplierSummaryResponse> list(
            @RequestParam(required = false) SupplierStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchasingSupplierService.list(status, search, pageable);
    }

    @GetMapping("/{id}")
    public PurchasingSupplierDetailResponse get(@PathVariable UUID id) {
        return purchasingSupplierService.get(id);
    }
}
