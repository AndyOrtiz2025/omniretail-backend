package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutResponse;
import com.omniretail.backend.ecommerce.service.StorefrontCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/{slug}/checkout")
@RequiredArgsConstructor
@Tag(
        name = "Checkout Público de la Tienda (Storefront)",
        description = "Procesamiento público del carrito y confirmación de pedidos/compras para la tienda en línea.")
public class PublicStorefrontCheckoutController {

    private final StorefrontCheckoutService checkoutService;

    @PostMapping
    @SecurityRequirements
    @Operation(
            summary = "Procesar checkout y generar pedido",
            description = "Registra y procesa una orden de compra desde el storefront público para el negocio (`slug`). Requiere el encabezado obligatorio `Idempotency-Key` para prevenir órdenes duplicadas.")
    public StorefrontCheckoutResponse checkout(
            @PathVariable String slug,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StorefrontCheckoutRequest request) {
        return checkoutService.checkout(slug, idempotencyKey, request);
    }
}
