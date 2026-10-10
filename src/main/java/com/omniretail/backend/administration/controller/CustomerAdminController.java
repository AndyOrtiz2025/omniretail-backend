package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CustomerAdminResponse;
import com.omniretail.backend.administration.service.CustomerAdminService;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
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
        name = "Customer administration",
        description = "Consulta y administración de clientes finales registrados en la tienda en línea o capturados desde puntos de venta."
)
@RestController
@RequestMapping("/administration/customers")
@RequiredArgsConstructor
public class CustomerAdminController {

    private final CustomerAdminService customerAdminService;

    @Operation(
            summary = "List customers",
            description = """
                    Recupera el padrón de clientes registrados en el tenant, permitiendo filtrar por su estado (`active`, `inactive`, `archived`).
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro opcional por estado (`active`, `inactive`, `archived`).
                    
                    **Permisos requeridos:**
                    * `admin.customers.read`
                    """
    )
    @RequirePermission("admin.customers.read")
    @GetMapping
    public List<CustomerAdminResponse> list(
            @RequestParam(required = false) CustomerStatus status) {
        return customerAdminService.listCustomers(status);
    }

    @Operation(
            summary = "Get customer by ID",
            description = """
                    Recupera la ficha completa de un cliente por su identificador único (datos de contacto, direcciones registradas, histórico resumido de actividad).
                    
                    **Permisos requeridos:**
                    * `admin.customers.read`
                    """
    )
    @RequirePermission("admin.customers.read")
    @GetMapping("/{id}")
    public CustomerAdminResponse getById(@PathVariable UUID id) {
        return customerAdminService.getCustomerById(id);
    }
}
