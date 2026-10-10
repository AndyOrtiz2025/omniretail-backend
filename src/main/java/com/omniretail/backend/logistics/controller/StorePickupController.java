package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.StorePickupHandoverResponse;
import com.omniretail.backend.logistics.service.StorePickupService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Entrega en Tienda (Store Pickup)",
        description = "Entrega de pedidos recogidos en tienda física por el cliente (Click & Collect), validación de identidad y cierre de entrega."
)
@RestController
@RequestMapping("/logistics/pickups")
@RequiredArgsConstructor
public class StorePickupController {

    private final StorePickupService storePickupService;

    @Operation(
            summary = "Registrar entrega de pedido en mostrador a cliente",
            description = """
                    Confirma que el cliente ha recibido en mano su paquete en la sucursal física seleccionada, finalizando el pedido con estado `DELIVERED`.
                    
                    **Permisos requeridos:**
                    * `logistics.dispatch.confirm`
                    """
    )
    @PostMapping("/{orderId}/handover")
    @RequirePermission("logistics.dispatch.confirm")
    public StorePickupHandoverResponse handover(
            @RequestParam UUID branchId, @PathVariable UUID orderId) {
        return storePickupService.handover(branchId, orderId);
    }
}
