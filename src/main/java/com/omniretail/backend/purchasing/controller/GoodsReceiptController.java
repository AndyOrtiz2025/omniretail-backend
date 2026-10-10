package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CreateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptResponse;
import com.omniretail.backend.purchasing.dto.UpdateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import com.omniretail.backend.purchasing.service.GoodsReceiptService;
import com.omniretail.backend.shared.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
        name = "Recepción de Mercancía en Almacén",
        description = "Ingreso físico de mercancía proveniente de órdenes de compra, validación de remisiones, captura de lotes/series y entrada definitiva al stock."
)
@RestController
@RequestMapping("/purchasing/receipts")
@RequiredArgsConstructor
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceiptService;

    @Operation(
            summary = "Listar recepciones de mercancía con paginación",
            description = """
                    Recupera el historial de recepciones de mercancía registradas en el almacén con filtros por sucursal, orden de compra origen y estado.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador de la sucursal receptora (opcional).
                    * `purchaseOrderId`: Identificador de la orden de compra (opcional).
                    * `status`: Filtro por estado (`draft`, `confirmed`).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20, máx. 100).
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `receiving.receipts.read`, `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @GetMapping
    public PageResponse<GoodsReceiptResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID purchaseOrderId,
            @RequestParam(required = false) GoodsReceiptStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return goodsReceiptService.list(branchId, purchaseOrderId, status, pageable);
    }

    @Operation(
            summary = "Obtener detalle de recepción de mercancía por ID",
            description = """
                    Recupera la ficha completa de recepción: partidas contadas, orden de compra origen, lotes y números de serie ingresados.
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `receiving.receipts.read`, `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @GetMapping("/{id}")
    public GoodsReceiptResponse get(@PathVariable UUID id) {
        return goodsReceiptService.get(id);
    }

    @Operation(
            summary = "Crear recepción de mercancía (Borrador)",
            description = """
                    Inicia el proceso de descarga física en estado `draft` para una orden de compra recepcionable (`approved`, `sent` o `partially_received`).
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoodsReceiptResponse create(@Valid @RequestBody CreateGoodsReceiptRequest request) {
        return goodsReceiptService.create(request);
    }

    @Operation(
            summary = "Actualizar recepción de mercancía borrador",
            description = """
                    Modifica las cantidades físicas contadas, ubicaciones o detalles de trazabilidad en una recepción en estado `draft`.
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @PutMapping("/{id}")
    public GoodsReceiptResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateGoodsReceiptRequest request) {
        return goodsReceiptService.update(id, request);
    }

    @Operation(
            summary = "Confirmar recepción de mercancía (Ingreso a Inventario)",
            description = """
                    Confirma la recepción (`confirmed`), acredita formalmente las existencias en el inventario de la sucursal receptora y actualiza el estado de la orden de compra (`partially_received` o `received`).
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos:**
                    * `receiving.receipts.confirm`
                    """
    )
    @PostMapping("/{id}/confirm")
    public GoodsReceiptResponse confirm(@PathVariable UUID id) {
        return goodsReceiptService.confirm(id);
    }

    @Operation(
            summary = "Eliminar recepción borrador",
            description = """
                    Descarta y elimina una recepción preliminar en estado `draft` que aún no ha impactado existencias de inventario.
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        goodsReceiptService.delete(id);
    }
}
