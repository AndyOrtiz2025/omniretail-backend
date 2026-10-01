package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderAdminService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentUser currentUser;

    public List<OrderAdminResponse> list(OrderStatus status) {
        UUID tenantId = currentUser.require().tenantId();
        List<Order> orders = status == null
                ? orderRepository.findByTenantIdAndSourceOrderByCreatedAtDesc(tenantId, OrderSource.ecommerce)
                : orderRepository.findByTenantIdAndSourceAndStatusOrderByCreatedAtDesc(
                        tenantId, OrderSource.ecommerce, status);
        return orders.stream().map(order -> toResponse(tenantId, order)).toList();
    }

    public OrderAdminResponse get(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Order order = findOrder(tenantId, id);
        return toResponse(tenantId, order);
    }

    @Transactional
    public OrderAdminResponse updateStatus(UUID id, OrderStatus nextStatus) {
        UUID tenantId = currentUser.require().tenantId();
        Order order = findOrder(tenantId, id);
        OrderStatus current = order.getStatus();
        if (!isAllowed(current, nextStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION",
                    "La transición de estado no está permitida.");
        }
        order.setStatus(nextStatus);
        return toResponse(tenantId, orderRepository.save(order));
    }

    private Order findOrder(UUID tenantId, UUID id) {
        Order order = orderRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "Pedido no encontrado."));
        if (order.getSource() != OrderSource.ecommerce) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido no encontrado.");
        }
        return order;
    }

    private OrderAdminResponse toResponse(UUID tenantId, Order order) {
        return OrderAdminResponse.of(order, orderItemRepository.findByOrderId(order.getId()),
                paymentRepository.findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(tenantId, order.getId()));
    }

    private static boolean isAllowed(OrderStatus current, OrderStatus next) {
        if (current == next) return true;
        if (next == OrderStatus.cancelled) {
            return current != OrderStatus.cancelled && current != OrderStatus.delivered;
        }
        return current == OrderStatus.pending && next == OrderStatus.confirmed;
    }
}
