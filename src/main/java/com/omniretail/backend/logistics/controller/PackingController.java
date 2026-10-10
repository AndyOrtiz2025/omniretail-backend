package com.omniretail.backend.logistics.controller;

import com.omniretail.backend.logistics.dto.PackingActionResponse;
import com.omniretail.backend.logistics.dto.PackingDetailResponse;
import com.omniretail.backend.logistics.dto.PackingFinalizeResponse;
import com.omniretail.backend.logistics.dto.PackingQueueResponse;
import com.omniretail.backend.logistics.dto.PackingVersionedRequest;
import com.omniretail.backend.logistics.dto.RegisterPackingLabelPrintRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.logistics.service.PackingService;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Logística de Empaque (Packing)",
        description = "Estación de embalaje y preparación de paquetes: acondicionamiento de bultos, pesaje, generación e impresión de guías y sellado de pedidos."
)
@RestController
@RequestMapping("/logistics/packing")
@RequiredArgsConstructor
public class PackingController {

    private final PackingService packingService;

    @Operation(
            summary = "Consultar cola de paquetes pendientes de empaque",
            description = """
                    Recupera la lista de órdenes recolectadas que se encuentran en espera de empaque en la sucursal.
                    
                    **Permisos requeridos:**
                    * `logistics.packing.read`
                    """
    )
    @GetMapping
    @RequirePermission("logistics.packing.read")
    public List<PackingQueueResponse> queue(@RequestParam UUID branchId) {
        return packingService.getQueue(branchId);
    }

    @Operation(
            summary = "Obtener detalle de empaque por ID",
            description = """
                    Recupera los artículos a embalar, dimensiones requeridas, empaque sugerido y estado de avance.
                    
                    **Permisos requeridos:**
                    * `logistics.packing.read`
                    """
    )
    @GetMapping("/{packingId}")
    @RequirePermission("logistics.packing.read")
    public PackingDetailResponse detail(
            @RequestParam UUID branchId, @PathVariable UUID packingId) {
        return packingService.getDetail(branchId, packingId);
    }

    @Operation(
            summary = "Guardar preparación física y medidas del paquete",
            description = """
                    Registra el número de bultos/cajas, peso total en kilogramos y dimensiones volumétricas de los paquetes preparados.
                    
                    **Permisos requeridos:**
                    * `logistics.packing.prepare`
                    """
    )
    @PatchMapping("/{packingId}/preparation")
    @RequirePermission("logistics.packing.prepare")
    public PackingActionResponse savePreparation(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody SavePackingPreparationRequest request) {
        return packingService.savePreparation(branchId, packingId, request);
    }

    @Operation(
            summary = "Generar etiqueta de paquetería",
            description = """
                    Genera el código y datos de la etiqueta de envío con el transportista asignado.
                    
                    **Permisos requeridos:**
                    * `logistics.packing.prepare`
                    """
    )
    @PostMapping("/{packingId}/label")
    @RequirePermission("logistics.packing.prepare")
    public PackingActionResponse generateLabel(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody PackingVersionedRequest request) {
        return packingService.generateLabel(branchId, packingId, request);
    }

    @Operation(
            summary = "Registrar impresión física de etiqueta",
            description = """
                    Confirma que la guía física ha sido impresa y adherida al bulto exterior.
                    
                    **Permisos requeridos:**
                    * `logistics.packing.prepare`
                    """
    )
    @PostMapping("/{packingId}/label/print")
    @RequirePermission("logistics.packing.prepare")
    public PackingActionResponse registerLabelPrint(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody RegisterPackingLabelPrintRequest request) {
        return packingService.registerLabelPrint(branchId, packingId, request);
    }

    @Operation(
            summary = "Finalizar empaque y sellar paquete",
            description = """
                    Concluye el proceso de embalaje, cambiando el estado del paquete a listo para despacho o recolección.
                    
                    **Permisos requeridos:**
                    * `logistics.packing.finalize`
                    """
    )
    @PostMapping("/{packingId}/finalize")
    @RequirePermission("logistics.packing.finalize")
    public PackingFinalizeResponse finalizePacking(
            @RequestParam UUID branchId,
            @PathVariable UUID packingId,
            @Valid @RequestBody PackingVersionedRequest request) {
        return packingService.finalizePacking(branchId, packingId, request);
    }
}
