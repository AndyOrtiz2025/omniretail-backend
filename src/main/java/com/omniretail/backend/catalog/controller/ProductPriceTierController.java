package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductSalesPriceTierResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductSalesPriceTiersRequest;
import com.omniretail.backend.catalog.service.ProductSalesPriceTierService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/products/{productId}/price-tiers")
@RequiredArgsConstructor
public class ProductPriceTierController {
    private final ProductSalesPriceTierService service;
    @GetMapping @RequirePermission("catalog.products.read")
    public List<ProductSalesPriceTierResponse> list(@PathVariable UUID productId) { return service.list(productId); }
    @PutMapping @RequirePermission("catalog.products.update")
    public List<ProductSalesPriceTierResponse> replace(@PathVariable UUID productId,
            @Valid @RequestBody ReplaceProductSalesPriceTiersRequest request) { return service.replace(productId, request); }
}
