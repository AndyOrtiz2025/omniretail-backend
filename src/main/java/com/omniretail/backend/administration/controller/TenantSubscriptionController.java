package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.TenantSubscriptionDetailsResponse;
import com.omniretail.backend.administration.dto.UpdateSubscriptionAddonsRequest;
import com.omniretail.backend.administration.service.TenantSubscriptionService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Tenant subscription",
        description = "Administración del plan activo del tenant, consumo de recursos contratados y gestión de complementos (addons)."
)
@RestController
@RequestMapping("/admin/subscriptions")
@RequiredArgsConstructor
public class TenantSubscriptionController {

    private final TenantSubscriptionService subscriptionService;

    @Operation(
            summary = "Get current tenant subscription",
            description = """
                    Recupera los detalles de la suscripción vigente del tenant autenticado: plan base asociado, addons contratados, límites asignados y estado de facturación.
                    
                    **Permisos requeridos:**
                    * `admin.plans.read`
                    """
    )
    @GetMapping
    @RequirePermission("admin.plans.read")
    public TenantSubscriptionDetailsResponse getCurrent() {
        return subscriptionService.getCurrent();
    }

    @Operation(
            summary = "Update subscription add-ons",
            description = """
                    Modifica los complementos (`addonCodes`) asignados a la suscripción activa del tenant (`ecommerce_delivery`, `advanced_reports`).
                    
                    **Permisos requeridos:**
                    * `admin.plans.manage`
                    """
    )
    @PutMapping("/addons")
    @RequirePermission("admin.plans.manage")
    public TenantSubscriptionDetailsResponse updateAddons(@Valid @RequestBody UpdateSubscriptionAddonsRequest request) {
        return subscriptionService.updateAddons(request);
    }
}
