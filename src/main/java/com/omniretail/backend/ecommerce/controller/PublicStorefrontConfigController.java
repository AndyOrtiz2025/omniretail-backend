package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.PublicStorefrontConfigResponse;
import com.omniretail.backend.ecommerce.service.PublicStorefrontConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/{slug}/config")
@RequiredArgsConstructor
@Tag(name = "Configuración pública de tienda")
public class PublicStorefrontConfigController {

    private final PublicStorefrontConfigService publicStorefrontConfigService;

    @GetMapping
    @Operation(summary = "Consultar configuración pública de una tienda")
    public PublicStorefrontConfigResponse get(@PathVariable String slug) {
        return publicStorefrontConfigService.getConfig(slug);
    }
}
