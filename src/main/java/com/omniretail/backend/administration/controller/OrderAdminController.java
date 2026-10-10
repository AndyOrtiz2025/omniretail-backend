package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.administration.dto.UpdateOrderStatusRequest;
import com.omniretail.backend.administration.service.OrderAdminService;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Gestión de Pedidos de E-commerce (Backoffice)",
        description = "Administración, consulta y avance del flujo de vida de los pedidos generados a través de la tienda virtual."
)
@RestController
@RequestMapping("/administration/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderAdminService orderAdminService;

    @Operation(
            summary = "Listar pedidos con paginación y filtros",
            description = """
                    Recupera el listado paginado de órdenes de compra del storefront con soporte de filtrado por estado.
                    
                    **Parámetros de consulta:**
                    * `status`: Filtro opcional por estado (`PENDING`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, `CANCELLED`).
                    * `page`: Número de página (base 0).
                    * `size`: Tamaño de página (por defecto 20).
                    
                    **Permisos requeridos:**
                    * `admin.orders.read`
                    """
    )
    @GetMapping
    @RequirePermission("admin.orders.read")
    public PageResponse<OrderAdminResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return orderAdminService.list(status, pageable);
    }

    @Operation(
            summary = "Obtener detalle completo de pedido",
            description = """
                    Recupera el detalle integral de una orden: productos adquiridos, desglose de impuestos, costos de flete, dirección de entrega y datos del comprador.
                    
                    **Permisos requeridos:**
                    * `admin.orders.read`
                    """
    )
    @GetMapping("/{id}")
    @RequirePermission("admin.orders.read")
    public OrderAdminResponse get(@PathVariable UUID id) {
        return orderAdminService.get(id);
    }

    @Operation(
            summary = "Actualizar estado de un pedido",
            description = """
                    Permite a un operador avanzar o modificar el estado del pedido en su ciclo de vida comercial o logístico.
                    
                    **Permisos requeridos:**
                    * `admin.orders.manage`
                    """
    )
    @PatchMapping("/{id}/status")
    @RequirePermission("admin.orders.manage")
    public OrderAdminResponse updateStatus(@PathVariable UUID id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderAdminService.updateStatus(id, request.status());
    }
}
