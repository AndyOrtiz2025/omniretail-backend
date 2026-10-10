package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.SaleReturnEligibilityResponse;
import com.omniretail.backend.pos.dto.SaleReturnOperationResponse;
import com.omniretail.backend.pos.dto.SaleReturnResponse;
import com.omniretail.backend.pos.service.SaleReturnService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Devoluciones de Venta en POS",
        description = "Validación de elegibilidad de tickets, procesamiento de devoluciones y reingreso de mercancía al stock."
)
@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class SaleReturnController {

    private final SaleReturnService service;

    @Operation(
            summary = "Procesar devolución de artículos de una venta",
            description = """
                    Ejecuta la devolución de una o varias partidas de un ticket de venta previamente cobrado: reincorpora los productos devueltos al almacén y registra el reembolso emitido.
                    
                    **Cabeceras:**
                    * `Idempotency-Key`: UUID único para garantizar que la devolución no se registre por duplicado en reintentos de red.
                    
                    **Permisos requeridos:**
                    * `pos.returns.create`
                    """
    )
    @PostMapping("/sales/{saleId}/returns")
    @RequirePermission("pos.returns.create")
    public ResponseEntity<?> create(
            @PathVariable UUID saleId,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody CreateSaleReturnRequest request) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(service.create(saleId, request));
        }
        SaleReturnOperationResponse response = service.create(saleId, idempotencyKey, request);
        return response.idempotent()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }

    @Operation(
            summary = "Consultar elegibilidad de devolución por folio de ticket",
            description = """
                    Verifica si una venta es apta para procesar devoluciones basándose en el número de documento/ticket, la sucursal y el límite temporal configurado en la política de reembolsos.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador único de la sucursal.
                    * `documentNumber`: Folio o número de ticket impreso.
                    
                    **Permisos requeridos:**
                    * `pos.returns.read`
                    """
    )
    @GetMapping("/sales/returns/eligibility")
    @RequirePermission("pos.returns.read")
    public SaleReturnEligibilityResponse eligibility(
            @RequestParam UUID branchId,
            @RequestParam String documentNumber) {
        return service.eligibility(branchId, documentNumber);
    }

    @Operation(
            summary = "Listar devoluciones de una sucursal con paginación",
            description = """
                    Recupera el historial paginado de todas las notas de crédito y devoluciones realizadas en la sucursal.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador único de la sucursal.
                    * `page`: Número de página (base 0).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `pos.returns.read`
                    """
    )
    @GetMapping("/returns")
    @RequirePermission("pos.returns.read")
    public Page<SaleReturnResponse> list(
            @RequestParam UUID branchId, Pageable pageable) {
        return service.list(branchId, pageable);
    }
}
