package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.OrderAdminResponse;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.ecommerce.service.OrderEmailNotifier;
import com.omniretail.backend.inventory.service.InventoryPhysicalSelectionCodec;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService.ReservationBalanceLocks;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService.PhysicalReservationRelease;
import com.omniretail.backend.logistics.entity.DispatchSourceType;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.DispatchRepository;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final CustomerRepository customerRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationLifecycleService reservationLifecycleService;
    private final PickingOrderRepository pickingOrderRepository;
    private final PickingItemRepository pickingItemRepository;
    private final PackingRepository packingRepository;
    private final DispatchRepository dispatchRepository;
    private final ProductRepository productRepository;
    private final InventoryPhysicalSelectionCodec physicalSelectionCodec;
    private final InventoryTraceabilityMutationService traceabilityMutationService;
    private final OrderEmailNotifier orderEmailNotifier;
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
        List<UUID> customerIds = orders.getContent().stream()
                .map(Order::getCustomerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> customerNames = customerIds.isEmpty()
                ? Map.of()
                : customerRepository.findByTenantIdAndIdIn(tenantId, customerIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Customer::getId, Customer::getName));
        return new PageResponse<>(orders.getContent().stream()
                .map(order -> OrderAdminResponse.of(order,
                        order.getCustomerId() == null ? null : customerNames.get(order.getCustomerId()),
                        items.getOrDefault(order.getId(), List.of()), payments.getOrDefault(order.getId(), List.of()), jsonMapper))
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
        if (nextStatus == OrderStatus.cancelled) {
            return cancel(tenantId, id);
        }
        Order order = findOrderForUpdate(tenantId, id);
        OrderStatus current = order.getStatus();
        if (!isAllowed(current, nextStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION",
                    "La transición de estado no está permitida.");
        }
        order.setStatus(nextStatus);
        Order saved = orderRepository.save(order);
        return toResponse(tenantId, saved);
    }

    private OrderAdminResponse cancel(UUID tenantId, UUID id) {
        Optional<PickingOrder> lockedPicking = lockPicking(tenantId, id);
        Order order = findOrderForUpdate(tenantId, id);
        if (lockedPicking.isEmpty()) {
            // Cierra la carrera con ensureForOrder: una vez bloqueado el Order ya no puede crearse otro Picking.
            lockedPicking = lockPicking(tenantId, id);
        }
        lockedPicking.ifPresent(picking -> requireMatchingPicking(order, picking));

        OrderStatus current = order.getStatus();
        if (current != OrderStatus.cancelled) {
            requireCancellationAllowed(tenantId, order, lockedPicking);
            List<InventoryReservation> activeReservations = reservationRepository
                    .findByTenantIdAndOrderId(tenantId, order.getId())
                    .stream()
                    .filter(reservation -> reservation.getStatus() == InventoryReservationStatus.active)
                    .toList();
            ReservationBalanceLocks lockedBalances = reservationLifecycleService.lockBalances(
                    tenantId, activeReservations);
            lockedPicking.filter(this::isCancellablePicking).ifPresent(this::cancelPicking);
            releaseReservations(tenantId, activeReservations, lockedBalances);
            order.setStatus(OrderStatus.cancelled);
        } else {
            lockedPicking.filter(this::isCancellablePicking).ifPresent(this::cancelPicking);
        }

        Order saved = orderRepository.save(order);
        if (current != OrderStatus.cancelled) {
            orderEmailNotifier.orderCancelled(saved);
        }
        return toResponse(tenantId, saved);
    }

    private Optional<PickingOrder> lockPicking(UUID tenantId, UUID orderId) {
        return pickingOrderRepository.findByTenantIdAndSourceTypeAndSourceIdForUpdate(
                tenantId, PickingSourceType.order, orderId);
    }

    private void requireCancellationAllowed(
            UUID tenantId, Order order, Optional<PickingOrder> lockedPicking) {
        if (!isAllowed(order.getStatus(), OrderStatus.cancelled)
                || lockedPicking.map(PickingOrder::getStatus)
                        .filter(status -> status == PickingStatus.completed)
                        .isPresent()
                || packingRepository.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                                tenantId, order.getBranchId(), PackingSourceType.order, order.getId())
                        .isPresent()
                || dispatchRepository.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(
                                tenantId, order.getBranchId(), DispatchSourceType.order, order.getId())
                        .isPresent()) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_ORDER_STATUS_TRANSITION",
                    "El pedido ya avanzo a una etapa que no admite cancelacion administrativa.");
        }
    }

    private static void requireMatchingPicking(Order order, PickingOrder picking) {
        if (!order.getId().equals(picking.getOrderId())
                || !order.getBranchId().equals(picking.getBranchId())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PICKING_SOURCE_CONFLICT",
                    "El Picking no coincide con su pedido.");
        }
    }

    private boolean isCancellablePicking(PickingOrder picking) {
        return picking.getStatus() == PickingStatus.pending
                || picking.getStatus() == PickingStatus.assigned
                || picking.getStatus() == PickingStatus.in_progress;
    }

    private void cancelPicking(PickingOrder picking) {
        releasePhysicalSelections(picking);
        picking.setStatus(PickingStatus.cancelled);
        picking.setAssignedUserId(null);
        pickingOrderRepository.save(picking);
    }

    private void releasePhysicalSelections(PickingOrder picking) {
        List<PickingItem> selectedItems = pickingItemRepository
                .findByScopeAndPickingOrderId(
                        picking.getTenantId(), picking.getBranchId(), picking.getId())
                .stream()
                .filter(item -> item.getPickedTraces() != null && !item.getPickedTraces().isBlank())
                .toList();
        if (selectedItems.isEmpty()) return;

        Map<UUID, Product> products = productRepository
                .findByTenantIdAndIdIn(
                        picking.getTenantId(),
                        selectedItems.stream().map(PickingItem::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<PhysicalReservationRelease> releases = selectedItems.stream().map(item -> {
            Product product = Optional.ofNullable(products.get(item.getProductId()))
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.CONFLICT,
                            "PICKING_PRODUCT_NOT_FOUND",
                            "Producto de Picking no encontrado."));
            return new PhysicalReservationRelease(
                    product,
                    item.getLocationId(),
                    physicalSelectionCodec.withoutLocation(
                            physicalSelectionCodec.decode(item.getPickedTraces()),
                            item.getLocationId()));
        }).toList();
        traceabilityMutationService.releasePhysicalReservations(
                picking.getTenantId(), picking.getBranchId(), releases);
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
        String customerName = order.getCustomerId() == null ? null : customerRepository
                .findByTenantIdAndId(tenantId, order.getCustomerId())
                .map(Customer::getName)
                .orElse(null);
        return OrderAdminResponse.of(order, customerName, orderItemRepository.findByOrderId(order.getId()),
                paymentRepository.findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(tenantId, order.getId()), jsonMapper);
    }

    private void releaseReservations(
            UUID tenantId,
            List<InventoryReservation> reservations,
            ReservationBalanceLocks lockedBalances) {
        for (InventoryReservation reservation : reservations) {
            reservationLifecycleService.release(
                    tenantId, reservation.getId(), lockedBalances);
        }
    }

    private static boolean isAllowed(OrderStatus current, OrderStatus next) {
        if (current == next) return true;
        if (next == OrderStatus.cancelled) {
            return current == OrderStatus.pending
                    || current == OrderStatus.confirmed
                    || current == OrderStatus.preparing
                    || current == OrderStatus.picking;
        }
        return current == OrderStatus.pending && next == OrderStatus.confirmed;
    }
}
