package com.omniretail.backend.pos.controller;

import com.omniretail.backend.administration.dto.CashShiftResponse;
import com.omniretail.backend.pos.dto.CloseCashShiftRequest;
import com.omniretail.backend.pos.dto.OpenCashShiftRequest;
import com.omniretail.backend.pos.service.CashShiftService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Turnos de Caja POS",
        description = "Operaciones de apertura, consulta de estado y cierre de turno de caja (arqueo y corte) en terminales de punto de venta."
)
@RestController
@RequestMapping("/pos/cash-shifts")
@RequiredArgsConstructor
public class CashShiftController {

    private final CashShiftService cashShiftService;

    @Operation(
            summary = "Consultar turno de caja abierto",
            description = """
                    Verifica si el usuario autenticado tiene un turno de caja actualmente abierto en la sucursal especificada.
                    Retorna el turno activo o código 204 (No Content) si no existe turno vigente.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador único de la sucursal.
                    
                    **Permisos requeridos:**
                    * `pos.cash.read`
                    """
    )
    @GetMapping("/open")
    @RequirePermission("pos.cash.read")
    public ResponseEntity<CashShiftResponse> openShift(@RequestParam java.util.UUID branchId) {
        return cashShiftService.findOpen(branchId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "Abrir turno de caja",
            description = """
                    Inicia un nuevo turno de caja registrando el fondo inicial de efectivo y la sucursal correspondiente.
                    
                    **Permisos requeridos:**
                    * `pos.cash.open`
                    """
    )
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("pos.cash.open")
    public CashShiftResponse open(@Valid @RequestBody OpenCashShiftRequest request) {
        return cashShiftService.open(request);
    }

    @Operation(
            summary = "Cerrar turno de caja (corte y arqueo)",
            description = """
                    Finaliza el turno de caja abierto, consolidando las ventas, registrando el dinero en efectivo contado físicamente y calculando sobrantes o faltantes.
                    
                    **Permisos requeridos:**
                    * `pos.cash.close`
                    """
    )
    @PostMapping("/close")
    @RequirePermission("pos.cash.close")
    public CashShiftResponse close(@Valid @RequestBody CloseCashShiftRequest request) {
        return cashShiftService.close(request);
    }
}
