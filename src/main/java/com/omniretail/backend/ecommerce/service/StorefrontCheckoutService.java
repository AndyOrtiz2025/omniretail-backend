package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutItemRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutResponse;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StorefrontCheckoutService {

    private final TenantRepository tenantRepository;
    private final EcommerceConfigRepository ecommerceConfigRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final PaymentRepository paymentRepository;
    private final TenantCapabilityGuard capabilityGuard;

    @Transactional
    public StorefrontCheckoutResponse checkout(
            String slug, String idempotencyKey, StorefrontCheckoutRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw BusinessException.badRequest("El encabezado Idempotency-Key es requerido y válido.");
        }
        Tenant tenant = tenantRepository.findBySlug(slug)
                .filter(found -> found.getStatus() == TenantStatus.active)
                .orElseThrow(() -> BusinessException.notFound("La tienda no existe."));
        UUID tenantId = tenant.getId();
        capabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);
        EcommerceConfig config = ecommerceConfigRepository.findByTenantId(tenantId)
                .filter(EcommerceConfig::isEnabled)
                .orElseThrow(() -> BusinessException.notFound("La tienda no está disponible."));
        if (!config.getAllowedDeliveryMethods().contains("home_delivery")
                || !config.getAllowedPaymentMethods().contains("card")) {
            throw BusinessException.badRequest("El checkout no está disponible para esta tienda.");
        }
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, config.getDefaultBranchId())
                .filter(found -> found.getStatus() == BranchStatus.active)
                .orElseThrow(() -> BusinessException.badRequest("La sucursal de despacho no está disponible."));

        Order existing = orderRepository.findByTenantIdAndSourceAndIdempotencyKey(
                tenantId, OrderSource.ecommerce, idempotencyKey).orElse(null);
        if (existing != null) {
            Payment payment = paymentRepository.findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(
                    tenantId, existing.getId()).stream().findFirst().orElseThrow();
            return response(existing, payment, orderItemRepository.findByOrderId(existing.getId()));
        }

        Customer customer = authenticatedCustomer(tenantId);
        if (config.isRequireAccountForCheckout() && customer == null) {
            throw BusinessException.forbidden("ACCOUNT_REQUIRED", "Debes iniciar sesión para comprar.");
        }
        List<StorefrontCheckoutItemRequest> requests = request.items();
        List<Product> products = new ArrayList<>();
        List<BigDecimal> quantities = new ArrayList<>();
        for (StorefrontCheckoutItemRequest line : requests) {
            Product product = productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                    tenantId, line.productId(), ProductStatus.published)
                    .orElseThrow(() -> BusinessException.notFound("Uno de los productos no está disponible."));
            products.add(product);
            quantities.add(line.quantity());
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            BigDecimal quantity = quantities.get(i);
            BigDecimal lineTotal = product.getSalePrice().multiply(quantity).setScale(2);
            subtotal = subtotal.add(lineTotal);
            items.add(OrderItem.builder()
                    .productId(product.getId())
                    .skuSnapshot(product.getSku())
                    .nameSnapshot(product.getName())
                    .quantity(quantity)
                    .inventoryQuantity(quantity)
                    .unitPrice(product.getSalePrice())
                    .discount(BigDecimal.ZERO)
                    .subtotal(lineTotal)
                    .build());
        }

        UUID orderSeed = UUID.randomUUID();
        Order order = Order.builder()
                .branchId(branch.getId())
                .orderNumber("WEB-" + orderSeed.toString().replace("-", "").substring(0, 10).toUpperCase())
                .source(OrderSource.ecommerce)
                .customerId(customer == null ? null : customer.getId())
                .guestCustomer(customer == null ? json(Map.of("name", request.fullName().trim(), "email", request.email().trim())) : null)
                .status(OrderStatus.pending)
                .deliveryMethod(DeliveryMethod.home_delivery)
                .transportMode(TransportMode.third_party)
                .deliveryAddress(json(address(request)))
                .notificationContact(json(Map.of("emailMode", "send", "email", request.email().trim())))
                .subtotal(subtotal)
                .discountTotal(BigDecimal.ZERO)
                .shippingTotal(BigDecimal.ZERO)
                .total(subtotal)
                .trackingToken(UUID.randomUUID().toString().replace("-", ""))
                .idempotencyKey(idempotencyKey)
                .build();
        order.setTenantId(tenantId);
        Order savedOrder = orderRepository.save(order);
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            item.setOrderId(savedOrder.getId());
            OrderItem savedItem = orderItemRepository.save(item);
            reserve(tenantId, branch.getId(), products.get(i).getId(), quantities.get(i));
            InventoryReservation reservation = InventoryReservation.builder()
                    .branchId(branch.getId())
                    .orderId(savedOrder.getId())
                    .orderItemId(savedItem.getId())
                    .productId(products.get(i).getId())
                    .allocations("[]")
                    .build();
            reservation.setTenantId(tenantId);
            reservationRepository.save(reservation);
        }
        Payment payment = Payment.builder()
                .orderId(savedOrder.getId())
                .method(PaymentMethod.card)
                .status(PaymentStatus.pending)
                .amount(subtotal)
                .currency(tenant.getDefaultCurrency())
                .reference("CARD-SIMULATED-" + request.cardLastFour())
                .build();
        payment.setTenantId(tenantId);
        payment = paymentRepository.save(payment);
        return response(savedOrder, payment, items);
    }

    private void reserve(UUID tenantId, UUID branchId, UUID productId, BigDecimal quantity) {
        InventoryBalance balance = balanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(tenantId, branchId, productId)
                .orElseThrow(() -> BusinessException.conflict("INSUFFICIENT_STOCK", "Stock insuficiente."));
        BigDecimal available = balance.getQuantity().subtract(balance.getReservedQuantity());
        if (quantity.compareTo(available) > 0) {
            throw BusinessException.conflict("INSUFFICIENT_STOCK", "Stock insuficiente.");
        }
        balance.setReservedQuantity(balance.getReservedQuantity().add(quantity));
    }

    private Customer authenticatedCustomer(UUID tenantId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) return null;
        return customerRepository.findByTenantIdAndUserIdAndStatus(tenantId, user.userId(), CustomerStatus.active).orElse(null);
    }

    private static Map<String, Object> address(StorefrontCheckoutRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recipientName", request.fullName().trim());
        value.put("recipientPhone", request.phone().trim());
        value.put("line1", request.addressLine1().trim());
        value.put("line2", request.addressLine2());
        value.put("city", request.city().trim());
        value.put("stateOrDepartment", request.department());
        value.put("country", "Guatemala");
        value.put("references", request.references());
        return value;
    }

    private static String json(Map<String, ?> values) {
        return values.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String quote(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + text + "\"";
    }

    private static StorefrontCheckoutResponse response(Order order, Payment payment, List<OrderItem> items) {
        return new StorefrontCheckoutResponse(order.getOrderNumber(), order.getTrackingToken(), order.getTotal(),
                order.getStatus(), payment.getStatus(), items.stream()
                        .map(item -> new StorefrontCheckoutResponse.Item(item.getSkuSnapshot(), item.getNameSnapshot(), item.getQuantity(), item.getSubtotal()))
                        .toList());
    }
}
