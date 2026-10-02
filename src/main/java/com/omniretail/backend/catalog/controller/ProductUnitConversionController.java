package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.ReplaceProductUnitConversionsRequest;
import com.omniretail.backend.catalog.dto.UnitConversionResponse;
import com.omniretail.backend.catalog.service.UnitConversionService;
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
@RequestMapping("/catalog/products/{productId}/unit-conversions")
@RequiredArgsConstructor
public class ProductUnitConversionController {
    private final UnitConversionService service;
    @GetMapping @RequirePermission("catalog.units.read")
    public List<UnitConversionResponse> list(@PathVariable UUID productId) { return service.listForProduct(productId); }
    @PutMapping @RequirePermission("catalog.units.manage")
    public List<UnitConversionResponse> replace(@PathVariable UUID productId,
            @Valid @RequestBody ReplaceProductUnitConversionsRequest request) { return service.replaceForProduct(productId, request); }
}
