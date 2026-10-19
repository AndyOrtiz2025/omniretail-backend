package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse;
import com.omniretail.backend.administration.service.TenantSubscriptionService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Tenant subscription",
        description = "Consulta informativa del plan del tenant, sus módulos contratados y el consumo de recursos. Los complementos los aprovisiona MARJYM; el tenant no puede cambiarlos."
)
@RestController
@RequestMapping("/admin/subscriptions")
@RequiredArgsConstructor
public class TenantSubscriptionController {

    private final TenantSubscriptionService subscriptionService;

    @Operation(
            summary = "Get current tenant subscription",
            description = """
                    Recupera los detalles de la suscripción vigente del tenant autenticado: plan base asociado, addons contratados, límites asignados y estado de facturación. Solo lectura: no genera registros de facturación.
                    
                    **Permisos requeridos:**
                    * `admin.plans.read`
                    """
    )
    @GetMapping
    @RequirePermission("admin.plans.read")
    public TenantSubscriptionDetailsResponse getCurrent() {
        return subscriptionService.getCurrent();
    }
}
