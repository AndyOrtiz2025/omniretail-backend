package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.ecommerce.dto.OrderTrackingResponse;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seguimiento publico de un pedido por token (resolveGuestOrderTracking y
 * GetStorefrontOrderTrackingService del frontend).
 *
 * <p>NO aplica {@code TenantCapabilityGuard(ecommerce)} a proposito: es acceso historico. Un cliente
 * que ya pago debe poder consultar su pedido aunque la tienda baje de plan.
 *
 * <p>Todos los casos de "no encontrado" (tienda inexistente o inactiva, e-commerce o seguimiento de
 * invitados deshabilitado, token inexistente, pedido de otro canal) responden el mismo 404, para no
 * revelar cual de las condiciones fallo.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderTrackingService {

    private final TenantRepository tenantRepository;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderTrackingResponse track(String slug, String trackingToken) {
        Tenant tenant = tenantRepository.findBySlug(slug)
                .filter(found -> found.getStatus() == TenantStatus.active)
                .orElseThrow(OrderTrackingService::notFound);
        ecommerceConfigRepository.findByTenantId(tenant.getId())
                .filter(EcommerceConfig::isEnabled)
                .filter(EcommerceConfig::isGuestTrackingEnabled)
                .orElseThrow(OrderTrackingService::notFound);
        Order order = orderRepository.findByTenantIdAndTrackingToken(tenant.getId(), trackingToken)
                // Nunca se exponen pedidos POS ni de otro canal.
                .filter(found -> found.getSource() == OrderSource.ecommerce)
                .orElseThrow(OrderTrackingService::notFound);
        return OrderTrackingResponse.from(order, orderItemRepository.findByOrderId(order.getId()));
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "ORDER_TRACKING_NOT_FOUND",
                "No encontramos un pedido con ese código de seguimiento.");
    }
}
