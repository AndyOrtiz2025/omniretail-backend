package com.omniretail.backend.catalog.controller;

import com.omniretail.backend.catalog.dto.UnitConversionCreateRequest;
import com.omniretail.backend.catalog.dto.UnitConversionResponse;
import com.omniretail.backend.catalog.dto.UnitConversionUpdateRequest;
import com.omniretail.backend.catalog.service.UnitConversionService;
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
        name = "Conversiones de Unidades",
        description = "Configuración de factores de equivalencia y conversión entre diferentes unidades de medida (globales o específicas por producto)."
)
@RestController
@RequestMapping("/catalog/unit-conversions")
@RequiredArgsConstructor
public class UnitConversionController {

    private final UnitConversionService unitConversionService;

    @Operation(
            summary = "Listar conversiones de unidades con paginación",
            description = """
                    Recupera los factores de conversión registrados con soporte de filtrado por producto específico y unidades origen/destino.
                    
                    **Parámetros de consulta:**
                    * `productId`: ID del producto si la conversión es específica para un artículo (opcional).
                    * `fromUnitId`: ID de la unidad origen (opcional).
                    * `toUnitId`: ID de la unidad destino (opcional).
                    * `page`: Número de página (base 0).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `catalog.units.read`
                    """
    )
    @GetMapping
    @RequirePermission("catalog.units.read")
    public PageResponse<UnitConversionResponse> list(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID fromUnitId,
            @RequestParam(required = false) UUID toUnitId,
            @PageableDefault(
                            size = 20,
                            sort = "createdAt",
                            direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return unitConversionService.list(productId, fromUnitId, toUnitId, pageable);
    }

    @Operation(
            summary = "Obtener detalle de conversión por ID",
            description = """
                    Recupera los detalles del factor multiplicador o divisor configurado entre dos unidades de medida.
                    
                    **Permisos requeridos:**
                    * `catalog.units.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("catalog.units.read")
    public UnitConversionResponse getById(@PathVariable UUID id) {
        return unitConversionService.getById(id);
    }

    @Operation(
            summary = "Crear nuevo factor de conversión",
            description = """
                    Registra una nueva relación de equivalencia entre unidades de medida (ejemplo: 1 Caja = 24 Piezas).
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("catalog.units.manage")
    public UnitConversionResponse create(
            @Valid @RequestBody UnitConversionCreateRequest request) {
        return unitConversionService.create(request);
    }

    @Operation(
            summary = "Actualizar factor de conversión",
            description = """
                    Actualiza el factor numérico de equivalencia o las unidades relacionadas.
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @PutMapping("/{id}")
    @RequirePermission("catalog.units.manage")
    public UnitConversionResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UnitConversionUpdateRequest request) {
        return unitConversionService.update(id, request);
    }

    @Operation(
            summary = "Eliminar factor de conversión",
            description = """
                    Elimina la relación de conversión configurada.
                    
                    **Permisos requeridos:**
                    * `catalog.units.manage`
                    """
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("catalog.units.manage")
    public void delete(@PathVariable UUID id) {
        unitConversionService.delete(id);
    }
}
