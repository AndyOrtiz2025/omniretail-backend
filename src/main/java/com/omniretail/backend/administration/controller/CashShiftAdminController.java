package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.administration.service.CashShiftAdminService;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Auditoría de Cortes de Caja (Backoffice)",
        description = "Supervisión administrativa de turnos y arqueos de caja en puntos de venta físicos por sucursal."
)
@RestController
@RequestMapping("/administration/cash-shifts")
@RequiredArgsConstructor
public class CashShiftAdminController {

    private final CashShiftAdminService cashShiftAdminService;

    @Operation(
            summary = "Listar turnos de caja de sucursales",
            description = """
                    Recupera el historial de turnos de caja del tenant con filtros por sucursal y estado (abierto, cerrado, auditado).
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro opcional por estado (`OPEN`, `CLOSED`).
                    * `branchId`: Identificador único de la sucursal a consultar (opcional).
                    
                    **Permisos requeridos:**
                    * `admin.cash.read`
                    """
    )
    @RequirePermission("admin.cash.read")
    @GetMapping
    public List<CashShiftResponse> list(
            @RequestParam(required = false) CashShiftStatus status,
            @RequestParam(required = false) UUID branchId) {
        return cashShiftAdminService.listCashShifts(status, branchId);
    }

    @Operation(
            summary = "Obtener detalle y arqueo de turno de caja",
            description = """
                    Recupera la auditoría desglosada de un turno de caja: saldo inicial, cobros por método de pago, retiros parciales, total esperado, monto declarado y faltante/sobrante.
                    
                    **Permisos requeridos:**
                    * `admin.cash.read`
                    """
    )
    @RequirePermission("admin.cash.read")
    @GetMapping("/{id}")
    public CashShiftResponse getById(@PathVariable UUID id) {
        return cashShiftAdminService.getCashShiftById(id);
    }
}
