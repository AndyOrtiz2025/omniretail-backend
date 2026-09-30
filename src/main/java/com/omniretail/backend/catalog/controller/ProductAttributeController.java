package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ProductAttributeValueResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductAttributesRequest;
import com.omniretail.backend.catalog.service.ProductAttributeService;
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
@RequestMapping("/catalog/products")
@RequiredArgsConstructor
public class ProductAttributeController {

    private final ProductAttributeService attributeService;

    @GetMapping("/{id}/attributes")
    @RequirePermission("catalog.products.read")
    public List<ProductAttributeValueResponse> get(@PathVariable UUID id) {
        return attributeService.get(id);
    }

    @PutMapping("/{id}/attributes")
    @RequirePermission("catalog.products.update")
    public List<ProductAttributeValueResponse> replace(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceProductAttributesRequest request) {
        return attributeService.replace(id, request);
    }
}
