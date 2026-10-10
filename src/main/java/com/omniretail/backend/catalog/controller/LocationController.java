package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.LocationCreateRequest;
import com.omniretail.backend.catalog.dto.LocationResponse;
import com.omniretail.backend.catalog.dto.LocationUpdateRequest;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.LocationType;
import com.omniretail.backend.catalog.service.LocationService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Ubicaciones Físicas de Almacén",
        description = "Administración de la topología física de almacenamiento en sucursales (zonas, pasillos, estanterías, racks y niveles)."
)
@RestController
@RequestMapping("/catalog/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @Operation(
            summary = "Listar ubicaciones de almacén con paginación",
            description = """
                    Recupera la lista paginada de ubicaciones físicas de almacén con múltiples criterios de filtrado por sucursal, padre jerárquico, tipo y estado.
                    
                    **Parámetros de consulta:**
                    * `branchId`: Identificador de la sucursal (opcional).
                    * `parentId`: Identificador de la ubicación padre en la jerarquía (opcional).
                    * `type`: Tipo de ubicación (`warehouse`, `aisle`, `shelf`, `level`).
                    * `status`: Estado de la ubicación (`active`, `inactive`, `archived`).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `catalog.locations.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.locations.read")
    public PageResponse<LocationResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) LocationType type,
            @RequestParam(required = false) LocationStatus status,
            @PageableDefault(
                            size = 20,
                            sort = "code",
                            direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return locationService.list(branchId, parentId, type, status, pageable);
    }

    @Operation(
            summary = "Obtener detalle de ubicación física por ID",
            description = """
                    Recupera la información completa de una posición de almacenamiento (código, nombre, tipo, jerarquía y sucursal).
                    
                    **Permisos requeridos:**
                    * `catalog.locations.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("catalog.locations.read")
    public LocationResponse getById(@PathVariable UUID id) {
        return locationService.getById(id);
    }

    @Operation(
            summary = "Crear nueva ubicación física de almacén",
            description = """
                    Registra una nueva posición o contenedor físico de almacenamiento en la sucursal designada.
                    
                    **Permisos requeridos:**
                    * `catalog.locations.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.locations.manage")
    public LocationResponse create(@Valid @RequestBody LocationCreateRequest request) {
        return locationService.create(request);
    }

    @Operation(
            summary = "Actualizar ubicación física existente",
            description = """
                    Modifica los datos descriptivos, código de localización o jerarquía de una posición de almacén.
                    
                    **Permisos requeridos:**
                    * `catalog.locations.manage`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.locations.manage")
    public LocationResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody LocationUpdateRequest request) {
        return locationService.update(id, request);
    }

    @Operation(
            summary = "Archivar o desactivar ubicación física",
            description = """
                    Desactiva la posición de almacenamiento, impidiendo que se le asignen nuevas existencias de inventario.
                    
                    **Permisos requeridos:**
                    * `catalog.locations.manage`
                    """
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.locations.manage")
    public void archive(@PathVariable UUID id) {
        locationService.archive(id);
    }
}
