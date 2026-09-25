package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.PublicStorefrontProductResponse;
import com.omniretail.backend.ecommerce.service.PublicStorefrontCatalogService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API sin autenticación para consultar el catálogo publicado de una tienda. */
@RestController
@RequestMapping("/public/{slug}/products")
@RequiredArgsConstructor
public class PublicStorefrontCatalogController {

    private final PublicStorefrontCatalogService publicStorefrontCatalogService;

    @GetMapping
    public List<PublicStorefrontProductResponse> list(@PathVariable String slug) {
        return publicStorefrontCatalogService.listProducts(slug);
    }

    @GetMapping("/{id}")
    public PublicStorefrontProductResponse getById(@PathVariable String slug, @PathVariable UUID id) {
        return publicStorefrontCatalogService.getProduct(slug, id);
    }
}
