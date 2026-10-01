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
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutItemRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutResponse;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
    private final InventoryReservationLifecycleService reservationLifecycleService;
    private final PickingService pickingService;
    private final PaymentRepository paymentRepository;
    private final TenantCapabilityGuard capabilityGuard;
    private final UnitConversionRepository unitConversionRepository;
    private final ProductPriceResolver productPriceResolver;
    private final JsonMapper jsonMapper;

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

        String fingerprint = fingerprint(request);
        Order existing = orderRepository.findByTenantIdAndSourceAndIdempotencyKey(
                tenantId, OrderSource.ecommerce, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.getIdempotencyFingerprint())) {
                throw BusinessException.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "La llave de idempotencia ya fue usada con un carrito distinto.");
            }
            pickingService.ensureForOrder(tenantId, existing.getId());
            Payment payment = paymentRepository.findByTenantIdAndOrderIdOrderByCreatedAtAscIdAsc(
                    tenantId, existing.getId()).stream().findFirst().orElseThrow();
            return response(
                    existing,
                    payment,
                    orderItemRepository.findByOrderId(existing.getId()),
                    config.isGuestTrackingEnabled());
        }

        Customer customer = authenticatedCustomer(tenantId);
        if (config.isRequireAccountForCheckout() && customer == null) {
            throw BusinessException.forbidden("ACCOUNT_REQUIRED", "Debes iniciar sesión para comprar.");
        }
        List<StorefrontCheckoutItemRequest> requests = request.items().stream()
                .sorted(Comparator.comparing(StorefrontCheckoutItemRequest::productId))
                .toList();
        List<Product> products = new ArrayList<>();
        List<BigDecimal> quantities = new ArrayList<>();
        for (StorefrontCheckoutItemRequest line : requests) {
            if (line.quantity().signum() <= 0 || line.quantity().stripTrailingZeros().scale() > 0) {
                throw BusinessException.badRequest("La cantidad debe ser un entero mayor a cero.");
            }
            Product product = productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                    tenantId, line.productId(), ProductStatus.published)
                    .orElseThrow(() -> BusinessException.notFound("Uno de los productos no está disponible."));
            products.add(product);
            quantities.add(line.quantity().setScale(0, RoundingMode.UNNECESSARY));
        }

        Instant pricingAt = Instant.now();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            BigDecimal quantity = quantities.get(i);
            ResolvedProductPrice resolved = productPriceResolver.resolveEffectivePrice(
                    tenantId, product, pricingAt);
            BigDecimal lineDiscount = resolved.discountAmount().multiply(quantity)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = resolved.effectivePrice().multiply(quantity)
                    .setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);
            discountTotal = discountTotal.add(lineDiscount);
            items.add(OrderItem.builder()
                    .productId(product.getId())
                    .promotionId(resolved.promotionId())
                    .skuSnapshot(product.getSku())
                    .nameSnapshot(product.getName())
                    .quantity(quantity)
                    .inventoryQuantity(inventoryQuantity(tenantId, product, quantity))
                    .unitPrice(product.getSalePrice())
                    .discount(lineDiscount)
                    .subtotal(lineTotal)
                    .build());
        }

        UUID orderSeed = UUID.randomUUID();
        Order order = Order.builder()
                .branchId(branch.getId())
                .orderNumber("WEB-" + orderSeed.toString().replace("-", "").substring(0, 10).toUpperCase())
                .source(OrderSource.ecommerce)
                .customerId(customer == null ? null : customer.getId())
                .guestCustomer(customer == null ? json(Map.of("name", request.fullName().trim(), "email", request.email().trim().toLowerCase())) : null)
                .status(OrderStatus.confirmed)
                .deliveryMethod(DeliveryMethod.home_delivery)
                .transportMode(TransportMode.third_party)
                .deliveryAddress(json(address(request)))
                .notificationContact(json(Map.of(
                        "emailMode", "send", "email", request.email().trim().toLowerCase())))
                .subtotal(subtotal)
                .discountTotal(discountTotal)
                .shippingTotal(BigDecimal.ZERO)
                .total(subtotal)
                .trackingToken(UUID.randomUUID().toString().replace("-", ""))
                .idempotencyKey(idempotencyKey)
                .idempotencyFingerprint(fingerprint)
                .build();
        order.setTenantId(tenantId);
        Order savedOrder = orderRepository.save(order);
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            item.setOrderId(savedOrder.getId());
            OrderItem savedItem = orderItemRepository.save(item);
            Product product = products.get(i);
            if (shouldReserve(product)) {
                reservationLifecycleService.reserve(new ReserveInventoryCommand(
                        tenantId,
                        branch.getId(),
                        product.getId(),
                        InventoryReservationSourceType.order,
                        savedOrder.getId(),
                        savedItem.getId(),
                        savedOrder.getId(),
                        savedItem.getId(),
                        savedItem.getInventoryQuantity()));
            }
        }
        pickingService.ensureForOrder(tenantId, savedOrder.getId());
        Payment payment = Payment.builder()
                .orderId(savedOrder.getId())
                .method(PaymentMethod.card)
                .status(PaymentStatus.approved)
                .amount(subtotal)
                .currency(tenant.getDefaultCurrency())
                .reference("CARD-SIMULATED-" + request.cardLastFour())
                .build();
        payment.setTenantId(tenantId);
        payment = paymentRepository.save(payment);
        return response(savedOrder, payment, items, config.isGuestTrackingEnabled());
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
        value.put("line2", trimToNull(request.addressLine2()));
        value.put("city", request.city().trim());
        value.put("stateOrDepartment", trimToNull(request.department()));
        value.put("country", "Guatemala");
        value.put("references", trimToNull(request.references()));
        return value;
    }

    private String json(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    private BigDecimal inventoryQuantity(UUID tenantId, Product product, BigDecimal quantity) {
        if (!shouldReserve(product) || product.getSaleUnitId() == null
                || product.getSaleUnitId().equals(product.getBaseUnitId())) return quantity;
        return unitConversionRepository.findByTenantIdAndProductId(tenantId, product.getId()).stream()
                        .filter(value -> value.getFromUnitId().equals(product.getSaleUnitId())
                                && value.getToUnitId().equals(product.getBaseUnitId()))
                        .findFirst()
                .or(() -> unitConversionRepository.findByTenantIdAndFromUnitIdAndToUnitIdAndProductIdIsNull(
                        tenantId, product.getSaleUnitId(), product.getBaseUnitId()))
                .map(value -> quantity.multiply(value.getFactor()).setScale(3, RoundingMode.HALF_UP))
                .orElseThrow(() -> BusinessException.badRequest(
                        "No existe una conversión de unidad de venta a unidad base para este producto."));
    }

    private static boolean shouldReserve(Product product) {
        return Boolean.TRUE.equals(product.getTrackingStock())
                && product.getProductType() == ProductType.physical;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String fingerprint(StorefrontCheckoutRequest request) {
        String payload = request.items().stream()
                .sorted(Comparator.comparing(StorefrontCheckoutItemRequest::productId))
                .map(item -> item.productId() + ":" + item.quantity().stripTrailingZeros().toPlainString())
                .collect(java.util.stream.Collectors.joining("|"))
                + "|" + request.fullName().trim()
                + "|" + request.email().trim().toLowerCase()
                + "|" + request.phone().trim()
                + "|" + request.addressLine1().trim()
                + "|" + String.valueOf(trimToNull(request.addressLine2()))
                + "|" + request.city().trim()
                + "|" + String.valueOf(trimToNull(request.department()))
                + "|" + String.valueOf(trimToNull(request.references()))
                + "|" + request.cardholderName().trim()
                + "|" + request.cardLastFour().trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private StorefrontCheckoutResponse response(
            Order order, Payment payment, List<OrderItem> items, boolean guestTrackingEnabled) {
        return new StorefrontCheckoutResponse(order.getOrderNumber(), order.getTrackingToken(), order.getTotal(),
                order.getStatus(), payment.getStatus(), guestTrackingEnabled,
                !reservationRepository.findByTenantIdAndOrderId(order.getTenantId(), order.getId()).isEmpty(),
                confirmationAddress(readAddress(order.getDeliveryAddress())), false, items.stream()
                        .map(item -> new StorefrontCheckoutResponse.Item(
                                item.getSkuSnapshot(),
                                item.getNameSnapshot(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getDiscount(),
                                item.getSubtotal(),
                                item.getPromotionId()))
                        .toList());
    }

    private Map<String, Object> readAddress(String value) {
        return jsonMapper.readValue(value, new TypeReference<>() {});
    }

    private static Map<String, Object> confirmationAddress(Map<String, Object> address) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recipientName", address.get("recipientName"));
        response.put("line1", address.get("line1"));
        response.put("line2", address.get("line2"));
        response.put("city", address.get("city"));
        response.put("department", address.get("stateOrDepartment"));
        response.put("phone", address.get("recipientPhone"));
        return response;
    }

}
