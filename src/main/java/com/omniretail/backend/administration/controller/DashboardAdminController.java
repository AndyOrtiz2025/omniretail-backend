package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.DashboardSummaryResponse;
import com.omniretail.backend.administration.service.DashboardAdminService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Tablero de Control y Métricas (Dashboard)",
        description = "Métricas consolidadas y KPIs operativos y financieros del negocio en tiempo real."
)
@RestController
@RequestMapping("/administration/dashboard")
@RequiredArgsConstructor
public class DashboardAdminController {

    private final DashboardAdminService dashboardAdminService;

    @Operation(
            summary = "Obtener resumen ejecutivo y KPIs del dashboard",
            description = """
                    Recupera los indicadores clave de desempeño (KPIs) para la pantalla principal de administración: ventas del día, pedidos pendientes de despacho, alertas de stock mínimo y balance de actividad reciente.
                    
                    **Permisos requeridos:**
                    * `admin.dashboard.read`
                    """
    )
    @RequirePermission("admin.dashboard.read")
    @GetMapping
    public DashboardSummaryResponse getDashboardSummary() {
        return dashboardAdminService.getDashboardSummary();
    }
}
