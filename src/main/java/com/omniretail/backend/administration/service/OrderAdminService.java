package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderAdminService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationLifecycleService reservationLifecycleService;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    public PageResponse<OrderAdminResponse> list(OrderStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<Order> orders = status == null
                ? orderRepository.findByTenantIdAndSource(tenantId, OrderSource.ecommerce, pageable)
                : orderRepository.findByTenantIdAndSourceAndStatus(tenantId, OrderSource.ecommerce, status, pageable);
        if (orders.isEmpty()) {
            return new PageResponse<>(List.of(), orders.getNumber() + 1, orders.getSize(),
                    orders.getTotalElements(), orders.getTotalPages());
        }
        Map<UUID, List<com.omniretail.backend.ecommerce.entity.OrderItem>> items = orderItemRepository
                .findByOrderIdIn(orders.getContent().stream().map(Order::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(com.omniretail.backend.ecommerce.entity.OrderItem::getOrderId));
        Map<UUID, List<com.omniretail.backend.pos.entity.Payment>> payments = paymentRepository
                .findByTenantIdAndOrderIdInOrderByCreatedAtAscIdAsc(tenantId,
                        orders.getContent().stream().map(Order::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(com.omniretail.backend.pos.entity.Payment::getOrderId));
        return new PageResponse<>(orders.getContent().stream()
                .map(order -> OrderAdminResponse.of(order, items.getOrDefault(order.getId(), List.of()),
                        payments.getOrDefault(order.getId(), List.of()), jsonMapper))
                .toList(), orders.getNumber() + 1, orders.getSize(), orders.getTotalElements(), orders.getTotalPages());
    }

    public OrderAdminResponse get(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Order order = findOrder(tenantId, id);
        return toResponse(tenantId, order);
    }

    @Transactional
    public OrderAdminResponse updateStatus(UUID id, OrderStatus nextStatus) {
        UUID tenantId = currentUser.require().tenantId();
        Order order = findOrderForUpdate(tenantId, id);
        OrderStatus current = order.getStatus();
        if (!isAllowed(current, nextStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION",
                    "La transición de estado no está permitida.");
        }
        if (nextStatus == OrderStatus.cancelled && current != OrderStatus.cancelled) {
            releaseReservations(tenantId, order.getId());
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

    private Order findOrderForUpdate(UUID tenantId, UUID id) {
        Order order = orderRepository.findByTenantIdAndIdForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "Pedido no encontrado."));
        if (order.getSource() != OrderSource.ecommerce) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido no encontrado.");
        }
        return order;
    }

    private OrderAdminResponse toResponse(UUID tenantId, Order order) {
        return OrderAdminResponse.of(order, orderItemRepository.findByOrderId(order.getId()),
                paymentRepository.findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(tenantId, order.getId()), jsonMapper);
    }

    private void releaseReservations(UUID tenantId, UUID orderId) {
        for (InventoryReservation reservation : reservationRepository.findByTenantIdAndOrderId(tenantId, orderId)) {
            if (reservation.getStatus() == InventoryReservationStatus.active) {
                reservationLifecycleService.release(tenantId, reservation.getId());
            }
        }
    }

    private static boolean isAllowed(OrderStatus current, OrderStatus next) {
        if (current == next) return true;
        if (next == OrderStatus.cancelled) {
            return current != OrderStatus.cancelled && current != OrderStatus.delivered;
        }
        return current == OrderStatus.pending && next == OrderStatus.confirmed;
    }
}
