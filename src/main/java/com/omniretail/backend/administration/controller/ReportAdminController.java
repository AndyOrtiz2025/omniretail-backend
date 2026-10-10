package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.ReportsDataResponse;
import com.omniretail.backend.administration.service.ReportAdminService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Reportes y Analítica",
        description = "Generación y consulta de reportes consolidados comerciales, de ventas y de rendimiento para la toma de decisiones."
)
@RestController
@RequestMapping("/administration/reports")
@RequiredArgsConstructor
public class ReportAdminController {

    private final ReportAdminService reportAdminService;

    @Operation(
            summary = "Obtener datos consolidados de reportes",
            description = """
                    Recupera el dataset estadístico para la generación de gráficas y reportes ejecutivos (ventas por sucursal, productos más vendidos, métodos de cobro y tendencias).
                    
                    **Permisos requeridos:**
                    * `admin.reports.read`
                    """
    )
    @RequirePermission("admin.reports.read")
    @GetMapping
    public ReportsDataResponse getReportsData() {
        return reportAdminService.getReportsData();
    }
}
