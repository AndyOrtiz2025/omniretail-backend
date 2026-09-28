package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.OrderTrackingResponse;
import com.omniretail.backend.ecommerce.service.OrderTrackingService;
import com.omniretail.backend.shared.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API sin autenticacion para que un cliente consulte su pedido con el token de seguimiento. */
@RestController
@RequestMapping("/public/{slug}/tracking")
@RequiredArgsConstructor
@Tag(name = "Seguimiento de pedidos", description = "Consulta pública del estado de un pedido de la tienda en línea.")
public class PublicOrderTrackingController {

    private final OrderTrackingService orderTrackingService;

    @GetMapping("/{token}")
    @SecurityRequirements
    @Operation(
            summary = "Consultar un pedido por su código de seguimiento",
            description = "Devuelve número, estado, total y productos de un pedido de la tienda en línea. No requiere "
                    + "sesión y nunca incluye datos del cliente. Cualquier motivo de \"no encontrado\" (tienda, "
                    + "configuración, código o canal) responde el mismo error genérico.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Estado actual del pedido.",
                content = @Content(
                        mediaType = "application/json", schema = @Schema(implementation = OrderTrackingResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No hay un pedido con ese código de seguimiento (`ORDER_TRACKING_NOT_FOUND`).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public OrderTrackingResponse track(@PathVariable String slug, @PathVariable String token) {
        return orderTrackingService.track(slug, token);
    }
}
