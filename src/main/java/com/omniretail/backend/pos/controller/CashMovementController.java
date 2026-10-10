package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CashMovementResponse;
import com.omniretail.backend.pos.dto.CreateCashMovementRequest;
import com.omniretail.backend.pos.service.CashMovementService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Movimientos de Caja (Entradas y Retiros)",
        description = "Registro de ingresos y egresos extraordinarios de efectivo en la gaveta del cajero (retiros parciales, gastos menores, aportes)."
)
@RestController
@RequestMapping("/pos/cash-movements")
@RequiredArgsConstructor
public class CashMovementController {

    private final CashMovementService service;

    @Operation(
            summary = "Registrar movimiento manual de efectivo",
            description = """
                    Registra un ingreso o egreso de dinero en la caja en turno con motivo y justificación (ej. retiro para depósito bancario, pago a proveedor menor).
                    
                    **Permisos requeridos:**
                    * `pos.cash.movement.create`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("pos.cash.movement.create")
    public CashMovementResponse create(@Valid @RequestBody CreateCashMovementRequest request) {
        return service.create(request);
    }

    @Operation(
            summary = "Listar movimientos de efectivo de un turno",
            description = """
                    Recupera el listado de ingresos y retiros de efectivo realizados en el turno de caja especificado.
                    
                    **Permisos requeridos:**
                    * `pos.cash.read`
                    """
    )
    @GetMapping("/shift/{cashShiftId}")
    @RequirePermission("pos.cash.read")
    public List<CashMovementResponse> list(@PathVariable java.util.UUID cashShiftId) {
        return service.listByShift(cashShiftId);
    }
}
