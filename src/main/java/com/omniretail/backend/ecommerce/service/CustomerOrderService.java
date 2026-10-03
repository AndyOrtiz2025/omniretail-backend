package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.dto.CustomerOrderDetailResponse;
import com.omniretail.backend.ecommerce.dto.CustomerOrderResponse;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Consulta el historial exclusivamente para el Customer asociado a la sesión actual. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CustomerOrderService {

    private final CurrentUser currentUser;
    private final CurrentCustomerResolver currentCustomerResolver;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final JsonMapper jsonMapper;

    public PageResponse<CustomerOrderResponse> list(Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        Customer customer = currentCustomerResolver.require(actor);
        Page<Order> orders = orderRepository.findByTenantIdAndCustomerIdAndSource(
                actor.tenantId(), customer.getId(), OrderSource.ecommerce, pageable);

        if (orders.isEmpty()) {
            return new PageResponse<>(List.of(), orders.getNumber() + 1, orders.getSize(),
                    orders.getTotalElements(), orders.getTotalPages());
        }

        Map<UUID, Integer> itemCounts = orderItemRepository
                .findByOrderIdIn(orders.getContent().stream().map(Order::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId, Collectors.summingInt(item -> 1)));

        return new PageResponse<>(
                orders.getContent().stream()
                        .map(order -> CustomerOrderResponse.from(order, itemCounts.getOrDefault(order.getId(), 0)))
                        .toList(),
                orders.getNumber() + 1,
                orders.getSize(),
                orders.getTotalElements(),
                orders.getTotalPages());
    }

    public CustomerOrderDetailResponse getById(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        Customer customer = currentCustomerResolver.require(actor);
        Order order = orderRepository.findByTenantIdAndId(actor.tenantId(), id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido no encontrado."));

        if (order.getCustomerId() == null || !order.getCustomerId().equals(customer.getId()) || order.getSource() != OrderSource.ecommerce) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido no encontrado.");
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<Payment> payments = paymentRepository.findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(actor.tenantId(), order.getId());
        Payment primaryPayment = payments.isEmpty() ? null : payments.getFirst();

        CustomerOrderDetailResponse.PaymentDto paymentDto = primaryPayment == null ? null :
                new CustomerOrderDetailResponse.PaymentDto(
                        primaryPayment.getMethod().name(),
                        primaryPayment.getStatus().name(),
                        primaryPayment.getReference());

        List<CustomerOrderDetailResponse.ItemDto> itemDtos = items.stream()
                .map(item -> new CustomerOrderDetailResponse.ItemDto(
                        item.getSkuSnapshot(),
                        item.getNameSnapshot(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .toList();

        return new CustomerOrderDetailResponse(
                order.getOrderNumber(),
                customerStatus(order.getStatus()),
                order.getCreatedAt(),
                order.getTrackingToken(),
                order.getSubtotal(),
                order.getShippingTotal(),
                order.getTotal(),
                parseAddress(order.getDeliveryAddress()),
                itemDtos,
                paymentDto);
    }

    private CustomerOrderDetailResponse.DeliveryAddressDto parseAddress(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = jsonMapper.readValue(rawJson, new TypeReference<>() {});
            return new CustomerOrderDetailResponse.DeliveryAddressDto(
                    stringValue(map.get("recipientName")),
                    stringValue(map.get("recipientPhone")),
                    stringValue(map.get("line1")),
                    stringValue(map.get("line2")),
                    stringValue(map.get("city")),
                    map.get("stateOrDepartment") != null ? stringValue(map.get("stateOrDepartment")) : stringValue(map.get("department")),
                    stringValue(map.get("country")),
                    stringValue(map.get("references")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stringValue(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private static String customerStatus(OrderStatus status) {
        return switch (status) {
            case pending -> "pending";
            case confirmed -> "confirmed";
            case preparing, picking, packing, ready_for_pickup, ready_for_dispatch -> "preparing";
            case dispatched -> "sent";
            case delivered -> "delivered";
            case cancelled -> "cancelled";
        };
    }
}
