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
        name = "Incidencias en Recepción de Mercancía",
        description = "Reporte y resolución de discrepancias en descarga de mercancía (piezas faltantes, dañadas, caducadas o reposiciones del proveedor)."
)
@RestController
@RequestMapping("/purchasing/receipts")
@RequiredArgsConstructor
public class ReceiptIncidentController {

    private final ReceiptIncidentService receiptIncidentService;

    @Operation(
            summary = "Listar incidencias de una recepción",
            description = """
                    Recupera el historial paginado de anomalías registradas en la recepción de mercancía especificada.
                    """
    )
    @GetMapping("/{receiptId}/incidents")
    public PageResponse<ReceiptIncidentResponse> list(
            @PathVariable UUID receiptId,
            @PageableDefault(size = 20) Pageable pageable) {
        return receiptIncidentService.list(receiptId, pageable);
    }

    @Operation(
            summary = "Registrar nueva incidencia de recepción",
            description = """
                    Levanta un reporte de incidencia sobre una partida recepcionada (ej. producto dañado, faltante respecto a factura, lote no conforme).
                    
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
            summary = "Resolver incidencia mediante reposición de mercancía",
            description = """
                    Liquida la incidencia registrando el ingreso físico del producto de reposición entregado por el proveedor.
                    
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
            summary = "Marcar incidencia como resuelta",
            description = """
                    Cierra administrativamente la incidencia (por ejemplo, tras emitirse una nota de crédito o acuerdo comercial).
                    
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
