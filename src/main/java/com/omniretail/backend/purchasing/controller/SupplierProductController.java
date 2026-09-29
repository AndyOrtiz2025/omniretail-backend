package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CreateSupplierProductRequest;
import com.omniretail.backend.purchasing.dto.ReplaceSupplierCostTiersRequest;
import com.omniretail.backend.purchasing.dto.SupplierCostTierResponse;
import com.omniretail.backend.purchasing.dto.SupplierProductResponse;
import com.omniretail.backend.purchasing.dto.UpdateSupplierProductRequest;
import com.omniretail.backend.purchasing.service.SupplierProductService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/purchasing/supplier-products")
@RequiredArgsConstructor
public class SupplierProductController {

    private final SupplierProductService supplierProductService;

    @RequirePermission("admin.suppliers.manage")
    @GetMapping
    public PageResponse<SupplierProductResponse> list(
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean preferred,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return supplierProductService.listAdmin(supplierId, productId, active, preferred, pageable);
    }

    @GetMapping("/active")
    public List<SupplierProductResponse> listOperational(
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID productId) {
        return supplierProductService.listOperational(supplierId, productId);
    }

    @RequirePermission("admin.suppliers.manage")
    @GetMapping("/{id}")
    public SupplierProductResponse get(@PathVariable UUID id) {
        return supplierProductService.getAdmin(id);
    }

    @RequirePermission("admin.suppliers.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierProductResponse create(@Valid @RequestBody CreateSupplierProductRequest request) {
        return supplierProductService.create(request);
    }

    @RequirePermission("admin.suppliers.manage")
    @PutMapping("/{id}")
    public SupplierProductResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateSupplierProductRequest request) {
        return supplierProductService.update(id, request);
    }

    @RequirePermission("admin.suppliers.manage")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        supplierProductService.archive(id);
    }

    @RequirePermission("admin.suppliers.manage")
    @PostMapping("/{id}/reactivate")
    public SupplierProductResponse reactivate(@PathVariable UUID id) {
        return supplierProductService.reactivate(id);
    }

    @RequirePermission("admin.suppliers.manage")
    @PostMapping("/{id}/preferred")
    public SupplierProductResponse setPreferred(@PathVariable UUID id) {
        return supplierProductService.setPreferred(id);
    }

    @RequirePermission("admin.suppliers.manage")
    @PutMapping("/{id}/cost-tiers")
    public List<SupplierCostTierResponse> replaceCostTiers(
            @PathVariable UUID id, @Valid @RequestBody ReplaceSupplierCostTiersRequest request) {
        return supplierProductService.replaceCostTiers(id, request);
    }
}
