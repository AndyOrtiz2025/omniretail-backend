package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductKitComponentResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductKitComponentsRequest;
import com.omniretail.backend.catalog.service.ProductKitService;
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
@RequestMapping("/catalog/products/{productId}/kit-components")
@RequiredArgsConstructor
public class ProductKitController {
    private final ProductKitService service;
    @GetMapping @RequirePermission("catalog.products.read")
    public List<ProductKitComponentResponse> list(@PathVariable UUID productId) { return service.list(productId); }
    @PutMapping @RequirePermission("catalog.products.update")
    public List<ProductKitComponentResponse> replace(@PathVariable UUID productId,
            @Valid @RequestBody ReplaceProductKitComponentsRequest request) { return service.replace(productId, request); }
}
