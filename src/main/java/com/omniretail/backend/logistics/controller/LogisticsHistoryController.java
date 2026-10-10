package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.LogisticsHistoryDetailResponse;
import com.omniretail.backend.logistics.dto.LogisticsHistoryRowResponse;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.service.LogisticsHistoryService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Historial y Auditoría Logística",
        description = "Consulta histórica unificada y trazabilidad completa de órdenes de preparación, empaque y despacho (pedidos de e-commerce y transferencias)."
)
@RestController
@RequestMapping("/logistics/history")
@RequiredArgsConstructor
public class LogisticsHistoryController {

    private final LogisticsHistoryService historyService;

    @Operation(
            summary = "Buscar en el historial de operaciones logísticas",
            description = """
                    Recupera el historial paginado de movimientos logísticos con filtros por estado de orden, método de entrega y rango de fechas.
                    
                    **Permisos requeridos:**
                    * `logistics.history.read`
                    """
    )
    @GetMapping
    @RequirePermission("logistics.history.read")
    public PageResponse<LogisticsHistoryRowResponse> search(
            @RequestParam UUID branchId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String transferStatus,
            @RequestParam(required = false) String deliveryMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return historyService.search(
                branchId, search, status, transferStatus, deliveryMethod, from, to, page, size);
    }

    @Operation(
            summary = "Obtener trazabilidad completa de un flujo logístico",
            description = """
                    Recupera la línea de tiempo auditada (eventos de recolección, embalaje, etiquetado y salida) para un pedido o transferencia.
                    
                    **Parámetros:**
                    * `sourceType`: Tipo de origen (`ORDER`, `TRANSFER`).
                    * `sourceId`: Identificador único de la orden u operación.
                    
                    **Permisos requeridos:**
                    * `logistics.history.read`
                    """
    )
    @GetMapping("/{sourceType}/{sourceId}")
    @RequirePermission("logistics.history.read")
    public LogisticsHistoryDetailResponse detail(
            @RequestParam UUID branchId,
            @PathVariable PickingSourceType sourceType,
            @PathVariable UUID sourceId) {
        return historyService.detail(branchId, sourceType, sourceId);
    }
}
