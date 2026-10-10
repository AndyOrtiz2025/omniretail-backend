package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.UnitCreateRequest;
import com.omniretail.backend.catalog.dto.UnitResponse;
import com.omniretail.backend.catalog.dto.UnitUpdateRequest;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.service.UnitService;
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
        name = "Unidades de Medida",
        description = "Administración de unidades métricas y comerciales para inventario, venta y empaque (kg, pieza, metro, etc.)."
)
@RestController
@RequestMapping("/catalog/units")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @Operation(
            summary = "Listar unidades de medida con paginación",
            description = """
                    Recupera el listado paginado de unidades de medida configuradas en el tenant, permitiendo filtrar por estado.
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro opcional por estado (`active`, `archived`).
                    * `page`: Número de página (comenzando en 1).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `catalog.units.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.units.read")
    public PageResponse<UnitResponse> list(
            @RequestParam(required = false) UnitStatus status,
            @PageableDefault(
                            size = 20,
                            sort = "name",
                            direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return unitService.list(status, pageable);
    }

    @Operation(
            summary = "Obtener detalle de unidad de medida por ID",
            description = """
                    Recupera los datos de una unidad de medida por su identificador único (nombre, símbolo/abreviatura y estado).
                    
                    **Permisos requeridos:**
                    * `catalog.units.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("catalog.units.read")
    public UnitResponse getById(@PathVariable UUID id) {
        return unitService.getById(id);
    }

    @Operation(
            summary = "Crear nueva unidad de medida",
            description = """
                    Registra una nueva unidad de medida en el catálogo (por ejemplo: PZ, KG, LTR, CJ).
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.units.manage")
    public UnitResponse create(@Valid @RequestBody UnitCreateRequest request) {
        return unitService.create(request);
    }

    @Operation(
            summary = "Actualizar unidad de medida existente",
            description = """
                    Actualiza el nombre, abreviatura o descripción de una unidad de medida existente.
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.units.manage")
    public UnitResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UnitUpdateRequest request) {
        return unitService.update(id, request);
    }

    @Operation(
            summary = "Archivar o desactivar unidad de medida",
            description = """
                    Marca la unidad de medida como inactiva/archivada. Las conversiones y productos existentes conservan su histórico.
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.units.manage")
    public void archive(@PathVariable UUID id) {
        unitService.archive(id);
    }
}
