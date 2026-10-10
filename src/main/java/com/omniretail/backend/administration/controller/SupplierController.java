package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CreateSupplierRequest;
import com.omniretail.backend.administration.dto.SupplierResponse;
import com.omniretail.backend.administration.dto.UpdateSupplierRequest;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.service.SupplierService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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

@RestController
@RequestMapping("/administration/suppliers")
@RequiredArgsConstructor
@Tag(name = "Proveedores", description = "Directorio maestro de proveedores, datos de contacto comercial y plazos de crédito.")
public class SupplierController {

    private final SupplierService supplierService;

    @RequirePermission("admin.suppliers.manage")
    @GetMapping
    @Operation(
            summary = "Listar proveedores paginados",
            description = "Devuelve los proveedores del negocio con soporte de paginación y filtro opcional por estado (`active`, `archived`).")
    public PageResponse<SupplierResponse> list(
            @RequestParam(required = false) SupplierStatus status, @PageableDefault(size = 20) Pageable pageable) {
        return supplierService.listSuppliers(status, pageable);
    }

    @RequirePermission("admin.suppliers.manage")
    @GetMapping("/active")
    @Operation(
            summary = "Listar proveedores activos",
            description = "Devuelve la lista simplificada de proveedores activos para órdenes de compra y selectores rápidos.")
    public List<SupplierResponse> listActive() {
        return supplierService.listActiveSuppliers();
    }

    @RequirePermission("admin.suppliers.manage")
    @GetMapping("/{id}")
    @Operation(
            summary = "Consultar proveedor por ID",
            description = "Obtiene los detalles comerciales, NIT, teléfono y términos de crédito de un proveedor.")
    public SupplierResponse getById(@PathVariable UUID id) {
        return supplierService.getSupplierById(id);
    }

    @RequirePermission("admin.suppliers.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Crear nuevo proveedor",
            description = "Registra un nuevo proveedor validando nombre y código único por negocio.")
    public SupplierResponse create(@Valid @RequestBody CreateSupplierRequest request) {
        return supplierService.createSupplier(request);
    }

    @RequirePermission("admin.suppliers.manage")
    @PutMapping("/{id}")
    @Operation(
            summary = "Actualizar proveedor",
            description = "Modifica los datos comerciales, dirección o contacto de un proveedor existente.")
    public SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSupplierRequest request) {
        return supplierService.updateSupplier(id, request);
    }

    @RequirePermission("admin.suppliers.manage")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Archivar proveedor",
            description = "Aplica borrado lógico archivando el proveedor para impedir nuevas órdenes de compra.")
    public void archive(@PathVariable UUID id) {
        supplierService.archiveSupplier(id);
    }
}
