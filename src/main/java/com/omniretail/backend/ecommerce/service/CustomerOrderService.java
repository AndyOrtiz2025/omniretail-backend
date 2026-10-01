package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.dto.CustomerOrderResponse;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
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

/** Consulta el historial exclusivamente para el Customer asociado a la sesión actual. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CustomerOrderService {

    private final CurrentUser currentUser;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public PageResponse<CustomerOrderResponse> list(Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        Customer customer = currentCustomer(actor);
        Page<Order> orders = orderRepository.findByTenantIdAndCustomerIdAndSource(
                actor.tenantId(), customer.getId(), OrderSource.ecommerce, pageable);

        if (orders.isEmpty()) {
            return new PageResponse<>(List.of(), orders.getNumber() + 1, orders.getSize(),
                    orders.getTotalElements(), orders.getTotalPages());
        }

        Map<UUID, Integer> itemCounts = orderItemRepository
                .findByOrderIdIn(orders.getContent().stream().map(Order::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(item -> item.getOrderId(), Collectors.summingInt(item -> 1)));

        return new PageResponse<>(
                orders.getContent().stream()
                        .map(order -> CustomerOrderResponse.from(order, itemCounts.getOrDefault(order.getId(), 0)))
                        .toList(),
                orders.getNumber() + 1,
                orders.getSize(),
                orders.getTotalElements(),
                orders.getTotalPages());
    }

    private Customer currentCustomer(AuthenticatedUser actor) {
        if (actor.userType() != UserType.customer) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCOUNT_REQUIRED",
                    "Esta consulta solo está disponible para clientes.");
        }
        return customerRepository.findByTenantIdAndUserIdAndStatus(
                        actor.tenantId(), actor.userId(), CustomerStatus.active)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCOUNT_REQUIRED",
                        "No se encontró una cuenta de cliente activa para la sesión."));
    }
}
