package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.catalog.service.ProductKitService;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutItemRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutResponse;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class StorefrontCheckoutServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private EcommerceConfigRepository ecommerceConfigRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private InventoryReservationRepository reservationRepository;
    @Mock private InventoryBalanceRepository balanceRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private TenantCapabilityGuard capabilityGuard;
    @Mock private UnitConversionRepository unitConversionRepository;
    @Mock private ProductPriceResolver productPriceResolver;
    @Mock private ProductKitService productKitService;

    private StorefrontCheckoutService service;
    private UUID tenantId;
    private UUID branchId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        service = new StorefrontCheckoutService(
                tenantRepository, ecommerceConfigRepository, branchRepository, productRepository,
                customerRepository, orderRepository, orderItemRepository, reservationRepository,
                balanceRepository, paymentRepository, capabilityGuard, unitConversionRepository,
                productPriceResolver,
                productKitService,
                JsonMapper.builder().build());
        tenantId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        productId = UUID.randomUUID();
        lenient().when(productPriceResolver.resolveEffectivePrice(
                        eq(tenantId), any(Product.class), any(), eq("ecommerce"), eq(branchId)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"),
                        new BigDecimal("20.00"),
                        BigDecimal.ZERO.setScale(2),
                        null));
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(tenant.getStatus()).thenReturn(TenantStatus.active);
        lenient().when(tenant.getDefaultCurrency()).thenReturn("GTQ");
        when(tenantRepository.findBySlug("ferreteria")).thenReturn(Optional.of(tenant));
        EcommerceConfig config = mock(EcommerceConfig.class);
        when(config.isEnabled()).thenReturn(true);
        lenient().when(config.isGuestTrackingEnabled()).thenReturn(true);
        when(config.getDefaultBranchId()).thenReturn(branchId);
        when(config.getAllowedDeliveryMethods()).thenReturn(List.of("home_delivery"));
        when(config.getAllowedPaymentMethods()).thenReturn(List.of("card"));
        when(ecommerceConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        Branch branch = mock(Branch.class);
        lenient().when(branch.getId()).thenReturn(branchId);
        when(branch.getStatus()).thenReturn(BranchStatus.active);
        when(branchRepository.findByTenantIdAndId(tenantId, branchId)).thenReturn(Optional.of(branch));
        when(orderRepository.findByTenantIdAndSourceAndIdempotencyKey(
                tenantId, OrderSource.ecommerce, "checkout-1")).thenReturn(Optional.empty());
    }

    @Test
    void guestCheckoutConfirmsPaymentAndStoresReservationAllocation() {
        Product product = product(true, ProductType.physical);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
        InventoryBalance balance = mock(InventoryBalance.class);
        UUID balanceId = UUID.randomUUID();
        when(balance.getId()).thenReturn(balanceId);
        when(balance.getQuantity()).thenReturn(new BigDecimal("3"));
        when(balance.getReservedQuantity()).thenReturn(BigDecimal.ZERO);
        when(balanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                tenantId, branchId, productId)).thenReturn(Optional.of(balance));
        Order savedOrder = mock(Order.class);
        UUID orderId = UUID.randomUUID();
        when(savedOrder.getId()).thenReturn(orderId);
        when(savedOrder.getOrderNumber()).thenReturn("WEB-123");
        when(savedOrder.getTrackingToken()).thenReturn("tracking");
        when(savedOrder.getTotal()).thenReturn(new BigDecimal("20.00"));
        when(savedOrder.getStatus()).thenReturn(OrderStatus.confirmed);
        when(savedOrder.getDeliveryAddress()).thenReturn(
                "{\"recipientName\":\"María López\",\"line1\":\"7a Avenida #1\","
                        + "\"line2\":null,\"city\":\"Guatemala\","
                        + "\"stateOrDepartment\":\"Guatemala\",\"recipientPhone\":\"55551234\"}");
        when(savedOrder.getTenantId()).thenReturn(tenantId);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        com.omniretail.backend.ecommerce.entity.OrderItem savedItem = mock(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        when(savedItem.getInventoryQuantity()).thenReturn(BigDecimal.ONE);
        when(orderItemRepository.save(any())).thenReturn(savedItem);
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.approved);
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(reservationRepository.findByTenantIdAndOrderId(tenantId, orderId)).thenReturn(List.of(mock(com.omniretail.backend.ecommerce.entity.InventoryReservation.class)));

        StorefrontCheckoutResponse response = service.checkout("ferreteria", "checkout-1", request(BigDecimal.ONE));

        assertThat(response.orderStatus()).isEqualTo(OrderStatus.confirmed);
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.approved);
        assertThat(response.guestTrackingEnabled()).isTrue();
        assertThat(response.deliveryAddress())
                .containsEntry("department", "Guatemala")
                .containsEntry("phone", "55551234");
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getIdempotencyFingerprint()).isNotBlank();
        assertThat(orderCaptor.getValue().getNotificationContact()).contains("maria@example.com");
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.approved);
        ArgumentCaptor<com.omniretail.backend.ecommerce.entity.InventoryReservation> reservationCaptor =
                ArgumentCaptor.forClass(com.omniretail.backend.ecommerce.entity.InventoryReservation.class);
        verify(reservationRepository).save(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().getAllocations())
                .contains("\"id\"", balanceId.toString(), "\"locationId\":null",
                        "\"reservedQuantity\":1", "\"consumedQuantity\":0");
    }

    @Test
    void checkoutRecalculatesPromotionAndStoresOrderItemSnapshot() {
        UUID promotionId = UUID.randomUUID();
        Product product = product(false, ProductType.service);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
        when(productPriceResolver.resolveEffectivePrice(eq(tenantId), any(Product.class), any(), eq("ecommerce"), eq(branchId)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("12.00"),
                        new BigDecimal("8.00"), promotionId));
        Order savedOrder = mock(Order.class);
        UUID orderId = UUID.randomUUID();
        when(savedOrder.getId()).thenReturn(orderId);
        when(savedOrder.getOrderNumber()).thenReturn("WEB-PROMO");
        when(savedOrder.getTrackingToken()).thenReturn("promo-tracking");
        when(savedOrder.getTotal()).thenReturn(new BigDecimal("24.00"));
        when(savedOrder.getStatus()).thenReturn(OrderStatus.confirmed);
        when(savedOrder.getDeliveryAddress()).thenReturn(
                "{\"recipientName\":\"Maria\",\"line1\":\"7a Avenida\","
                        + "\"line2\":null,\"city\":\"Guatemala\","
                        + "\"stateOrDepartment\":null,\"recipientPhone\":\"55551234\"}");
        when(savedOrder.getTenantId()).thenReturn(tenantId);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.approved);
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(reservationRepository.findByTenantIdAndOrderId(tenantId, orderId)).thenReturn(List.of());

        StorefrontCheckoutResponse response = service.checkout(
                "ferreteria", "checkout-1", request(new BigDecimal("2")));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSubtotal()).isEqualByComparingTo("24.00");
        assertThat(orderCaptor.getValue().getDiscountTotal()).isEqualByComparingTo("16.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("24.00");
        ArgumentCaptor<com.omniretail.backend.ecommerce.entity.OrderItem> itemCaptor =
                ArgumentCaptor.forClass(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        verify(orderItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getUnitPrice()).isEqualByComparingTo("20.00");
        assertThat(itemCaptor.getValue().getDiscount()).isEqualByComparingTo("16.00");
        assertThat(itemCaptor.getValue().getSubtotal()).isEqualByComparingTo("24.00");
        assertThat(itemCaptor.getValue().getPromotionId()).isEqualTo(promotionId);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.discount()).isEqualByComparingTo("16.00");
            assertThat(item.promotionId()).isEqualTo(promotionId);
        });
    }

    @Test
    void kitCheckoutReservesEachComponentAndStoresSnapshot() {
        UUID componentId = UUID.randomUUID();
        Product kit = product(false, ProductType.kit);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(kit));
        when(productKitService.fulfillment(tenantId, kit, new BigDecimal("2")))
                .thenReturn(List.of(new ProductKitService.FulfillmentComponent(
                        componentId, new BigDecimal("3.000"), new BigDecimal("6.000"))));
        InventoryBalance balance = mock(InventoryBalance.class);
        when(balance.getId()).thenReturn(UUID.randomUUID());
        when(balance.getQuantity()).thenReturn(new BigDecimal("10.000"));
        when(balance.getReservedQuantity()).thenReturn(BigDecimal.ZERO);
        when(balanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                tenantId, branchId, componentId)).thenReturn(Optional.of(balance));
        Order savedOrder = mock(Order.class); UUID orderId = UUID.randomUUID();
        when(savedOrder.getId()).thenReturn(orderId); when(savedOrder.getOrderNumber()).thenReturn("WEB-KIT");
        when(savedOrder.getTrackingToken()).thenReturn("kit"); when(savedOrder.getTotal()).thenReturn(new BigDecimal("40.00"));
        when(savedOrder.getStatus()).thenReturn(OrderStatus.confirmed);
        when(savedOrder.getDeliveryAddress()).thenReturn("{\"city\":\"Guatemala\"}");
        when(savedOrder.getTenantId()).thenReturn(tenantId); when(orderRepository.save(any())).thenReturn(savedOrder);
        when(orderItemRepository.save(any())).thenAnswer(call -> {
            var item = call.getArgument(0, com.omniretail.backend.ecommerce.entity.OrderItem.class);
            ReflectionTestUtils.setField(item, "id", UUID.randomUUID()); return item;
        });
        Payment payment = mock(Payment.class); when(payment.getStatus()).thenReturn(PaymentStatus.approved);
        when(paymentRepository.save(any())).thenReturn(payment);
        when(reservationRepository.findByTenantIdAndOrderId(tenantId, orderId)).thenReturn(List.of());

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("2")));

        verify(reservationRepository).save(org.mockito.ArgumentMatchers.argThat(reservation ->
                reservation.getProductId().equals(componentId)
                        && reservation.getAllocations().contains("\"reservedQuantity\":6.000")));
        verify(orderItemRepository).save(org.mockito.ArgumentMatchers.argThat(item ->
                item.getProductId().equals(productId)
                        && item.getFulfillmentComponents().contains(componentId.toString())));
    }

    @Test
    void rejectsFractionalQuantityBeforePersistingAnything() {
        assertThatThrownBy(() -> service.checkout("ferreteria", "checkout-1", request(new BigDecimal("0.333"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("entero");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void rejectsUnavailableProduct() {
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkout("ferreteria", "checkout-1", request(BigDecimal.ONE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no está disponible");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void rejectsInsufficientStock() {
        Product product = product(true, ProductType.physical);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
        InventoryBalance balance = mock(InventoryBalance.class);
        when(balance.getQuantity()).thenReturn(BigDecimal.ZERO);
        when(balance.getReservedQuantity()).thenReturn(BigDecimal.ZERO);
        when(balanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                tenantId, branchId, productId)).thenReturn(Optional.of(balance));
        Order savedOrder = mock(Order.class);
        when(savedOrder.getId()).thenReturn(UUID.randomUUID());
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        com.omniretail.backend.ecommerce.entity.OrderItem savedItem =
                mock(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        when(savedItem.getInventoryQuantity()).thenReturn(BigDecimal.ONE);
        when(orderItemRepository.save(any())).thenReturn(savedItem);

        assertThatThrownBy(() -> service.checkout("ferreteria", "checkout-1", request(BigDecimal.ONE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void rejectsAnIdempotencyKeyReusedWithAnotherCart() {
        Order existing = mock(Order.class);
        when(existing.getIdempotencyFingerprint()).thenReturn("another-fingerprint");
        when(orderRepository.findByTenantIdAndSourceAndIdempotencyKey(
                tenantId, OrderSource.ecommerce, "checkout-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.checkout("ferreteria", "checkout-1", request(BigDecimal.ONE)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void customerCheckoutDoesNotPersistGuestSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customerRepository.findByTenantIdAndUserIdAndStatus(any(), eq(userId), any()))
                .thenReturn(Optional.of(customer));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(userId, tenantId, UserType.customer, null, null, UUID.randomUUID()),
                null));
        try {
            Product product = product(false, ProductType.service);
            when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                    tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
            Order savedOrder = mock(Order.class);
            UUID orderId = UUID.randomUUID();
            when(savedOrder.getId()).thenReturn(orderId);
            when(savedOrder.getOrderNumber()).thenReturn("WEB-123");
            when(savedOrder.getTrackingToken()).thenReturn("tracking");
            when(savedOrder.getTotal()).thenReturn(new BigDecimal("20.00"));
            when(savedOrder.getStatus()).thenReturn(OrderStatus.confirmed);
            when(savedOrder.getDeliveryAddress()).thenReturn("{\"city\":\"Guatemala\"}");
            when(savedOrder.getTenantId()).thenReturn(tenantId);
            when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
            Payment payment = mock(Payment.class);
            when(payment.getStatus()).thenReturn(PaymentStatus.approved);
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            when(reservationRepository.findByTenantIdAndOrderId(tenantId, orderId)).thenReturn(List.of());

            service.checkout("ferreteria", "checkout-1", request(BigDecimal.ONE));

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(orderCaptor.capture());
            assertThat(orderCaptor.getValue().getCustomerId()).isEqualTo(customerId);
            assertThat(orderCaptor.getValue().getGuestCustomer()).isNull();
            verify(balanceRepository, never()).findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                    any(), any(), any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Product product(boolean trackingStock, ProductType type) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getSku()).thenReturn("SKU-1");
        when(product.getName()).thenReturn("Martillo");
        when(product.getSalePrice()).thenReturn(new BigDecimal("20.00"));
        when(product.getTrackingStock()).thenReturn(trackingStock);
        lenient().when(product.getProductType()).thenReturn(type);
        if (trackingStock) {
            when(product.getSaleUnitId()).thenReturn(null);
        }
        return product;
    }

    private StorefrontCheckoutRequest request(BigDecimal quantity) {
        return new StorefrontCheckoutRequest(
                List.of(new StorefrontCheckoutItemRequest(productId, quantity)),
                "María López", "MARIA@EXAMPLE.COM", "55551234", "7a Avenida #1",
                "", "Guatemala", "", "", "María López", "4242");
    }
}
