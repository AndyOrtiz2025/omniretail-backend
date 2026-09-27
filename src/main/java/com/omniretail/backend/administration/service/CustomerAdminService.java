package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.CustomerAdminResponse;
import com.omniretail.backend.administration.dto.CustomerAdminResponse.CustomerProductPurchaseDto;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.text.Collator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CustomerAdminService {

    // Orden alfabético del español (Á junto a A, Ñ después de N), equivalente a localeCompare del frontend.
    private static final Locale SPANISH = Locale.of("es");

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final CurrentUser currentUser;

    public List<CustomerAdminResponse> listCustomers(CustomerStatus status) {
        UUID tenantId = currentUser.require().tenantId();
        List<Customer> customers = status != null
                ? customerRepository.findByTenantIdAndStatus(tenantId, status)
                : customerRepository.findByTenantId(tenantId);
        if (customers.isEmpty()) {
            return List.of();
        }

        return enrichCustomers(
                customers,
                orderRepository.findByTenantIdAndStatusNot(tenantId, OrderStatus.cancelled),
                orderItemRepository.findActiveItemsByTenantId(tenantId),
                saleRepository.findByTenantIdAndStatusNot(tenantId, SaleStatus.cancelled),
                saleItemRepository.findActiveItemsByTenantId(tenantId));
    }

    // Solo carga las compras de este cliente: la regla anti-duplicados compara pedidos del mismo cliente,
    // así que el resultado es idéntico al del listado.
    public CustomerAdminResponse getCustomerById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Customer customer = customerRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Cliente no encontrado."));

        return enrichCustomers(
                        List.of(customer),
                        orderRepository.findByTenantIdAndCustomerIdAndStatusNot(tenantId, id, OrderStatus.cancelled),
                        orderItemRepository.findActiveItemsByTenantIdAndCustomerId(tenantId, id),
                        saleRepository.findByTenantIdAndCustomerIdAndStatusNot(tenantId, id, SaleStatus.cancelled),
                        saleItemRepository.findActiveItemsByTenantIdAndCustomerId(tenantId, id))
                .getFirst();
    }

    private static List<CustomerAdminResponse> enrichCustomers(
            List<Customer> customers,
            List<Order> orders,
            List<OrderItem> orderItems,
            List<Sale> sales,
            List<SaleItem> saleItems) {
        Map<UUID, List<OrderItem>> orderItemsByOrderId = orderItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        Map<UUID, List<SaleItem>> saleItemsBySaleId = saleItems.stream()
                .collect(Collectors.groupingBy(SaleItem::getSaleId));

        Map<UUID, Long> purchaseCounts = new HashMap<>();
        Map<UUID, Map<String, BigDecimal>> productQuantities = new HashMap<>();
        Map<UUID, UUID> countedOrdersById = new HashMap<>();

        for (Order order : orders) {
            if (order.getCustomerId() == null) continue;
            UUID customerId = order.getCustomerId();
            purchaseCounts.merge(customerId, 1L, Long::sum);
            countedOrdersById.put(order.getId(), customerId);

            List<OrderItem> items = orderItemsByOrderId.getOrDefault(order.getId(), List.of());
            for (OrderItem item : items) {
                Map<String, BigDecimal> customerProducts =
                        productQuantities.computeIfAbsent(customerId, k -> new HashMap<>());
                customerProducts.merge(item.getNameSnapshot(), item.getQuantity(), BigDecimal::add);
            }
        }

        for (Sale sale : sales) {
            if (sale.getCustomerId() == null) continue;
            UUID customerId = sale.getCustomerId();
            if (sale.getSourceOrderId() != null && customerId.equals(countedOrdersById.get(sale.getSourceOrderId()))) {
                continue;
            }
            purchaseCounts.merge(customerId, 1L, Long::sum);

            List<SaleItem> items = saleItemsBySaleId.getOrDefault(sale.getId(), List.of());
            for (SaleItem item : items) {
                Map<String, BigDecimal> customerProducts =
                        productQuantities.computeIfAbsent(customerId, k -> new HashMap<>());
                customerProducts.merge(item.getNameSnapshot(), item.getQuantity(), BigDecimal::add);
            }
        }

        Collator collator = Collator.getInstance(SPANISH);
        return customers.stream()
                .map(customer -> {
                    Map<String, BigDecimal> products = productQuantities.get(customer.getId());
                    List<CustomerProductPurchaseDto> topProducts = products != null
                            ? products.entrySet().stream()
                                    .map(entry -> new CustomerProductPurchaseDto(entry.getKey(), entry.getValue()))
                                    .sorted(Comparator.comparing(CustomerProductPurchaseDto::totalQuantity)
                                            .reversed()
                                            .thenComparing(CustomerProductPurchaseDto::productName, collator))
                                    .toList()
                            : List.of();
                    long purchases = purchaseCounts.getOrDefault(customer.getId(), 0L);
                    return CustomerAdminResponse.of(customer, purchases, topProducts);
                })
                .sorted(Comparator.comparingLong(CustomerAdminResponse::purchaseCount)
                        .reversed()
                        .thenComparing(CustomerAdminResponse::name, collator))
                .toList();
    }
}
