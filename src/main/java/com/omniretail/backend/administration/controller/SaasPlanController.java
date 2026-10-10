package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.SaasPlanResponse;
import com.omniretail.backend.administration.service.SaasPlanService;
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
        name = "Planes SaaS",
        description = "Consulta y administración de niveles de suscripción de la plataforma multinquilino (cuotas de usuarios, sucursales y características habilitadas)."
)
@RestController
@RequestMapping("/admin/plans")
@RequiredArgsConstructor
public class SaasPlanController {

    private final SaasPlanService planService;

    @Operation(
            summary = "Listar planes SaaS disponibles",
            description = """
                    Recupera el catálogo de planes de suscripción ofrecidos por la plataforma, con opción de filtrar únicamente aquellos vigentes y contratables.
                    
                    **Parámetros de consulta:**
                    * `activeOnly`: Si es `true`, filtra solo planes en estado activo (por defecto `false`).
                    
                    **Permisos requeridos:**
                    * `admin.plans.read`
                    """
    )
    @RequirePermission("admin.plans.read")
    @GetMapping
    public List<SaasPlanResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return planService.list(activeOnly);
    }

    @Operation(
            summary = "Obtener detalle de un plan SaaS por ID",
            description = """
                    Recupera la ficha técnica y comercial de un plan: costo recurrente, límite de sucursales, tope de usuarios concurrentes y módulos disponibles.
                    
                    **Permisos requeridos:**
                    * `admin.plans.read`
                    """
    )
    @RequirePermission("admin.plans.read")
    @GetMapping("/{id}")
    public SaasPlanResponse get(@PathVariable UUID id) {
        return planService.get(id);
    }
}
