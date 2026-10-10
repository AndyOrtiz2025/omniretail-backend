package com.omniretail.backend.purchasing.controller;

import com.omniretail.backend.purchasing.dto.CreateReceiptIncidentRequest;
import com.omniretail.backend.purchasing.dto.ReceiptIncidentResponse;
import com.omniretail.backend.purchasing.dto.ResolveReceiptIncidentWithReplacementRequest;
import com.omniretail.backend.purchasing.service.ReceiptIncidentService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Goods receipt incidents",
        description = "Reporte y resolución de discrepancias en descarga de mercancía (piezas faltantes, dañadas, caducadas o reposiciones del proveedor)."
)
@RestController
@RequestMapping("/purchasing/receipts")
@RequiredArgsConstructor
public class ReceiptIncidentController {

    private final ReceiptIncidentService receiptIncidentService;

    @Operation(
            summary = "List goods receipt incidents",
            description = """
                    Recupera el historial paginado (`page` comenzando en 1) de anomalías registradas en la recepción de mercancía especificada.
                    
                    **Permisos requeridos (cualquiera de ellos):**
                    * `receiving.receipts.read`, `receiving.receipts.create` o `receiving.receipts.confirm`
                    """
    )
    @GetMapping("/{receiptId}/incidents")
    public PageResponse<ReceiptIncidentResponse> list(
            @PathVariable UUID receiptId,
            @PageableDefault(size = 20) Pageable pageable) {
        return receiptIncidentService.list(receiptId, pageable);
    }

    @Operation(
            summary = "Create goods receipt incident",
            description = """
                    Levanta un reporte de incidencia sobre una partida recepcionada (`incidentType`: `missing`, `damaged`, `wrong_item`, `expired`, `other`).
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos:**
                    * `receiving.incidents.manage`
                    """
    )
    @PostMapping("/{receiptId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("receiving.incidents.manage")
    public ReceiptIncidentResponse create(
            @PathVariable UUID receiptId,
            @Valid @RequestBody CreateReceiptIncidentRequest request) {
        return receiptIncidentService.create(receiptId, request);
    }

    @Operation(
            summary = "Resolve incident with replacement",
            description = """
                    Liquida la incidencia (`resolved`) registrando el ingreso físico del producto de reposición entregado por el proveedor.
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos:**
                    * `receiving.incidents.manage`
                    """
    )
    @PostMapping("/incidents/{incidentId}/resolve-with-replacement")
    @RequirePermission("receiving.incidents.manage")
    public ReceiptIncidentResponse resolveWithReplacement(
            @PathVariable UUID incidentId,
            @Valid @RequestBody ResolveReceiptIncidentWithReplacementRequest request) {
        return receiptIncidentService.resolveWithReplacement(incidentId, request);
    }

    @Operation(
            summary = "Mark incident as resolved",
            description = """
                    Cierra administrativamente la incidencia (`resolved`).
                    
                    **Capacidad SaaS requerida:**
                    * `receiving`
                    
                    **Permisos requeridos:**
                    * `receiving.incidents.manage`
                    """
    )
    @PatchMapping("/incidents/{incidentId}/resolve")
    @RequirePermission("receiving.incidents.manage")
    public ReceiptIncidentResponse resolve(@PathVariable UUID incidentId) {
        return receiptIncidentService.resolve(incidentId);
    }
}
