package com.omniretail.backend.inventory.controller;

import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizationOptionsResponse;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.service.InventoryBalanceRegularizationService;
import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regularizacion administrativa del inventario heredado sin ubicacion. La vista previa solo requiere
 * lectura de stock (mas acceso a la sucursal); la ejecucion exige la misma autorizacion que un ajuste de
 * inventario, y la asignacion inicial de la ubicacion operativa exige ademas actualizar productos. No se
 * introduce un permiso nuevo ni se amplia ninguno existente.
 */
@RestController
@RequestMapping("/inventory/location-regularizations")
@RequiredArgsConstructor
@Tag(
        name = "Inventory location regularization",
        description = "Consolida el saldo heredado sin ubicación de un producto en su ubicación operativa, "
                + "con asignación inicial opcional de esa ubicación.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Sin token, token inválido o vencido. Responde sin cuerpo."),
    @ApiResponse(
            responseCode = "403",
            description = "Sin el permiso requerido (`ACCESS_DENIED`) o sin acceso a la sucursal "
                    + "(`BRANCH_ACCESS_DENIED`).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
})
public class InventoryBalanceRegularizationController {

    private static final String JSON_MEDIA_TYPE = "application/json";

    private final InventoryBalanceRegularizationService regularizationService;

    @GetMapping("/preview")
    @RequirePermission("inventory.stock.read")
    @Operation(
            summary = "Preview a legacy balance regularization",
            description = """
                    Solo lectura (`inventory.stock.read` y acceso a la sucursal). Describe qué se movería al destino \
                    y los bloqueos que impedirían ejecutarlo; `snapshotFingerprint` y las cantidades esperadas se \
                    reenvían al ejecutar.

                    Con `assign=true` evalúa además la asignación inicial del destino cuando el producto aún no \
                    tiene ubicación operativa. La huella incluye la asignación vigente y el modo: una vista previa \
                    solo sirve para ejecutar en su mismo modo.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Vista previa (`eligible=false` si hay `blockers`).",
                content = @Content(
                        mediaType = JSON_MEDIA_TYPE,
                        schema = @Schema(implementation = LegacyBalanceRegularizationPreviewResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Sucursal, producto o ubicación inexistentes en el tenant.",
                content = @Content(mediaType = JSON_MEDIA_TYPE, schema = @Schema(implementation = ApiError.class)))
    })
    public LegacyBalanceRegularizationPreviewResponse preview(
            @Parameter(description = "Sucursal del inventario.") @RequestParam UUID branchId,
            @Parameter(description = "Producto físico con control de inventario.") @RequestParam UUID productId,
            @Parameter(description = "Ubicación destino: la operativa asignada, o la que se asignará.")
                    @RequestParam UUID locationId,
            @Parameter(description = "true para evaluar la asignación inicial del destino.")
                    @RequestParam(defaultValue = "false") boolean assign) {
        return regularizationService.preview(branchId, productId, locationId, assign);
    }

    @GetMapping("/options")
    @RequirePermission("inventory.stock.read")
    @Operation(
            summary = "List regularization destination options",
            description = """
                    Solo lectura (`inventory.stock.read` y acceso a la sucursal). Devuelve la ubicación asignada \
                    del producto y las ubicaciones activas de esa sucursal que podrían asignarse. Nunca incluye \
                    ubicaciones de otra sucursal; con el control de ubicaciones apagado la lista va vacía.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Ubicación asignada y ubicaciones asignables.",
                content = @Content(
                        mediaType = JSON_MEDIA_TYPE,
                        schema = @Schema(implementation = RegularizationOptionsResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Sucursal o producto inexistentes en el tenant.",
                content = @Content(mediaType = JSON_MEDIA_TYPE, schema = @Schema(implementation = ApiError.class)))
    })
    public RegularizationOptionsResponse options(
            @Parameter(description = "Sucursal del inventario.") @RequestParam UUID branchId,
            @Parameter(description = "Producto físico con control de inventario.") @RequestParam UUID productId) {
        return regularizationService.options(branchId, productId);
    }

    /**
     * 201 cuando la regularizacion se aplica; 200 cuando es el reintento idempotente de una ya aplicada. Con
     * {@code assignDestination: true} la asignacion inicial exige ademas {@code catalog.products.update}
     * (verificado en el servicio solo cuando la asignacion realmente se aplica).
     */
    @PostMapping
    @RequirePermission("inventory.adjustment.create")
    @Operation(
            summary = "Regularize a legacy balance",
            description = """
                    Consolida en una sola transacción el saldo sin ubicación (cantidad, reservado, lotes y series) \
                    en la ubicación operativa del producto y reasigna las reservas activas. Requiere \
                    `inventory.adjustment.create`.

                    Con `assignDestination: true` y un producto sin ubicación asignada, asigna también el destino \
                    en esa misma transacción y exige además `catalog.products.update`. Nunca cambia una asignación \
                    existente.

                    Idempotente por `idempotencyKey`: la misma clave y la misma solicitud devuelven el resultado \
                    registrado (200); la misma clave con otra solicitud responde `KEY_REUSED`.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Regularización aplicada.",
                content = @Content(
                        mediaType = JSON_MEDIA_TYPE,
                        schema = @Schema(implementation = LegacyBalanceRegularizationResultResponse.class))),
        @ApiResponse(
                responseCode = "200",
                description = "Reintento idempotente: resultado ya registrado, sin mover inventario otra vez.",
                content = @Content(
                        mediaType = JSON_MEDIA_TYPE,
                        schema = @Schema(implementation = LegacyBalanceRegularizationResultResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Cuerpo inválido (`VALIDATION_ERROR`) o producto no elegible.",
                content = @Content(mediaType = JSON_MEDIA_TYPE, schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Bloqueos de inventario o de política, vista previa obsoleta "
                        + "(`INVENTORY_REGULARIZATION_STALE_SNAPSHOT`), clave reutilizada o "
                        + "`INVENTORY_REGULARIZATION_BUSY` (nada aplicado, reintentable).",
                content = @Content(mediaType = JSON_MEDIA_TYPE, schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<LegacyBalanceRegularizationResultResponse> regularize(
            @Valid @RequestBody RegularizeLegacyBalanceRequest request) {
        LegacyBalanceRegularizationResultResponse result = regularizationService.regularize(request);
        return ResponseEntity.status(result.idempotent() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
