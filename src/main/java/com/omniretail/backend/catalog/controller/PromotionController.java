package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.CreatePromotionRequest;
import com.omniretail.backend.catalog.dto.PromotionResponse;
import com.omniretail.backend.catalog.dto.PromotionSummaryResponse;
import com.omniretail.backend.catalog.dto.UpdatePromotionRequest;
import com.omniretail.backend.catalog.service.PromotionService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Promociones y Descuentos",
        description = "Administración de campañas comerciales, promociones temporales, descuentos por porcentaje o monto fijo y vigencias."
)
@RestController
@RequestMapping("/catalog/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    @Operation(
            summary = "Listar promociones con paginación",
            description = """
                    Recupera el listado paginado de promociones comerciales registradas en el tenant, permitiendo filtrar por un producto particular.
                    
                    **Parámetros de consulta:**
                    * `productId`: Identificador del producto a consultar promociones asociadas (opcional).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `catalog.promotions.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.promotions.read")
    public PageResponse<PromotionSummaryResponse> list(@RequestParam(required = false) UUID productId, Pageable pageable) {
        return promotionService.list(productId, pageable);
    }

    @Operation(
            summary = "Obtener detalle de promoción por ID",
            description = """
                    Recupera la información completa de una promoción: tipo de descuento (`percentage`, `fixed_discount`, `fixed_price`), valor, fechas de vigencia, estado (`active`, `ended`, `cancelled`) y artículos incluidos.
                    
                    **Permisos requeridos:**
                    * `catalog.promotions.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("catalog.promotions.read")
    public PromotionResponse get(@PathVariable UUID id) {
        return promotionService.get(id);
    }

    @Operation(
            summary = "Crear nueva promoción",
            description = """
                    Registra una nueva promoción o campaña de descuento en el catálogo comercial.
                    
                    **Permisos requeridos:**
                    * `catalog.promotions.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.promotions.manage")
    public PromotionResponse create(@Valid @RequestBody CreatePromotionRequest request) {
        return promotionService.create(request);
    }

    @Operation(
            summary = "Actualizar datos de una promoción",
            description = """
                    Modifica los parámetros comerciales, fechas de vigencia o alcance de una promoción existente.
                    
                    **Permisos requeridos:**
                    * `catalog.promotions.manage`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.promotions.manage")
    public PromotionResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePromotionRequest request) {
        return promotionService.update(id, request);
    }

    @Operation(
            summary = "Finalizar promoción anticipadamente",
            description = """
                    Da por terminada de manera inmediata una promoción activa, desactivando los precios promocionales asociados.
                    
                    **Permisos requeridos:**
                    * `catalog.promotions.manage`
                    """
    )
    @PutMapping("/{id}/end")
    @RequirePermission("catalog.promotions.manage")
    public PromotionResponse end(@PathVariable UUID id) { return promotionService.end(id); }

    @Operation(
            summary = "Cancelar promoción",
            description = """
                    Cancela una promoción programada o activa, revocando sus beneficios comerciales.
                    
                    **Permisos requeridos:**
                    * `catalog.promotions.manage`
                    """
    )
    @PutMapping("/{id}/cancel")
    @RequirePermission("catalog.promotions.manage")
    public PromotionResponse cancel(@PathVariable UUID id) {
        return promotionService.cancel(id);
    }
}
