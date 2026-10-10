package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.PublicStorefrontProductResponse;
import com.omniretail.backend.ecommerce.service.PublicStorefrontCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Public storefront catalog",
        description = "Endpoints públicos para consultar el catálogo de productos publicados de la tienda online sin requerir autenticación.")
public class PublicStorefrontCatalogController {

    private final PublicStorefrontCatalogService publicStorefrontCatalogService;

    @GetMapping
    @SecurityRequirements
    @Operation(
            summary = "List storefront products",
            description = "Obtiene el listado completo de productos activos y publicados para la tienda identificada por su slug.")
    public List<PublicStorefrontProductResponse> list(@PathVariable String slug) {
        return publicStorefrontCatalogService.listProducts(slug);
    }

    @GetMapping("/{id}")
    @SecurityRequirements
    @Operation(
            summary = "Get storefront product by ID",
            description = "Obtiene el detalle completo de un producto específico publicado en la tienda por su identificador UUID.")
    public PublicStorefrontProductResponse getById(@PathVariable String slug, @PathVariable UUID id) {
        return publicStorefrontCatalogService.getProduct(slug, id);
    }
}
