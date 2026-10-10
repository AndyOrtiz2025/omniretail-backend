package com.omniretail.backend.pos.controller;

import com.omniretail.backend.pos.dto.CashShiftSummaryResponse;
import com.omniretail.backend.pos.service.CashShiftSummaryService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Resumen de Turno de Caja POS",
        description = "Consulta de totales acumulados, desglose por forma de pago y balance en tiempo real del turno de caja."
)
@RestController
@RequestMapping("/pos/cash-shifts")
@RequiredArgsConstructor
public class CashShiftSummaryController {

    private final CashShiftSummaryService cashShiftSummaryService;

    @Operation(
            summary = "Obtener balance y resumen acumulado del turno de caja",
            description = """
                    Calcula y retorna en tiempo real las ventas acumuladas por método de pago (efectivo, tarjeta, transferencia), entradas/salidas de dinero y el efectivo proyectado en gaveta para el turno especificado.
                    
                    **Permisos requeridos:**
                    * `pos.cash.read`
                    """
    )
    @GetMapping("/{cashShiftId}/summary")
    @RequirePermission("pos.cash.read")
    public CashShiftSummaryResponse summary(@PathVariable UUID cashShiftId) {
        return cashShiftSummaryService.getSummary(cashShiftId);
    }
}
