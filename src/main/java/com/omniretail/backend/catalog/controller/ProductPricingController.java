package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductPriceHistoryResponse;
import com.omniretail.backend.catalog.dto.UpdateProductPriceRequest;
import com.omniretail.backend.catalog.service.ProductPricingService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductPricingController {

    private final ProductPricingService pricingService;

    @PutMapping("/{id}/price")
    @RequirePermission("catalog.products.update")
    public ProductDto updatePrice(
            @PathVariable UUID id, @Valid @RequestBody UpdateProductPriceRequest request) {
        return pricingService.updatePrice(id, request);
    }

    @GetMapping("/{id}/price-history")
    @RequirePermission("catalog.products.read")
    public PageResponse<ProductPriceHistoryResponse> history(
            @PathVariable UUID id, Pageable pageable) {
        return pricingService.history(id, pageable);
    }
}
