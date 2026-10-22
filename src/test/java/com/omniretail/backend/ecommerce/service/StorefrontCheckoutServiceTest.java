package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.catalog.entity.ProductSalesPriceTier;
import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import com.omniretail.backend.catalog.repository.ProductSalesPriceTierRepository;
import com.omniretail.backend.catalog.repository.PromotionRepository;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;
import com.omniretail.backend.administration.dto.ShippingChannelConfigDto;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.ShippingChannel;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.ShippingConfigService;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.catalog.service.ProductKitService;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutItemRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutResponse;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.logistics.service.PickingService;
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
    @Mock private InventoryReservationLifecycleService reservationLifecycleService;
    @Mock private PickingService pickingService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private TenantCapabilityGuard capabilityGuard;
    @Mock private UnitConversionRepository unitConversionRepository;
    @Mock private ProductPriceResolver productPriceResolver;
    @Mock private ProductKitService productKitService;
    @Mock private OrderEmailNotifier orderEmailNotifier;
    @Mock private ShippingConfigService shippingConfigService;

    private StorefrontCheckoutService service;
    private UUID tenantId;
    private UUID branchId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        service = new StorefrontCheckoutService(
                tenantRepository, ecommerceConfigRepository, branchRepository, productRepository,
                customerRepository, orderRepository, orderItemRepository, reservationRepository,
                reservationLifecycleService, pickingService, paymentRepository, capabilityGuard,
                unitConversionRepository,
                productPriceResolver,
                productKitService,
                JsonMapper.builder().build(),
                orderEmailNotifier,
                shippingConfigService);
        // Sin configuración guardada: tarifa por defecto del canal (Q25, gratis desde Q300).
        lenient().when(shippingConfigService.calculate(any(), eq(ShippingChannel.ecommerce), any()))
                .thenAnswer(invocation -> ShippingConfigService.calculate(
                        ShippingConfigService.ECOMMERCE_DEFAULT, invocation.getArgument(2)));
        tenantId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        productId = UUID.randomUUID();
        lenient().when(productPriceResolver.resolveEffectivePrice(
                        eq(tenantId), any(Product.class), any(), eq("ecommerce"), eq(branchId), any()))
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
        UUID orderItemId = UUID.randomUUID();
        when(savedItem.getId()).thenReturn(orderItemId);
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
        assertThat(orderCaptor.getValue().getShippingTotal()).isEqualByComparingTo("25.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("45.00");
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.approved);
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("45.00");
        ArgumentCaptor<ReserveInventoryCommand> reservationCaptor =
                ArgumentCaptor.forClass(ReserveInventoryCommand.class);
        verify(reservationLifecycleService).reserve(reservationCaptor.capture());
        verify(pickingService).ensureForOrder(tenantId, orderId);
        assertThat(reservationCaptor.getValue().sourceType())
                .isEqualTo(InventoryReservationSourceType.order);
        assertThat(reservationCaptor.getValue().sourceId()).isEqualTo(orderId);
        assertThat(reservationCaptor.getValue().sourceLineId()).isEqualTo(orderItemId);
        assertThat(reservationCaptor.getValue().orderId()).isEqualTo(orderId);
        assertThat(reservationCaptor.getValue().orderItemId()).isEqualTo(orderItemId);
        assertThat(reservationCaptor.getValue().quantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void checkoutRecalculatesPromotionAndStoresOrderItemSnapshot() {
        UUID promotionId = UUID.randomUUID();
        Product product = product(false, ProductType.service);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
        when(productPriceResolver.resolveEffectivePrice(eq(tenantId), any(Product.class), any(), eq("ecommerce"), eq(branchId), any()))
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
        assertThat(orderCaptor.getValue().getShippingTotal()).isEqualByComparingTo("25.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("49.00");
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
    void checkoutOffersFreeShippingAtTheThreshold() {
        Product product = product(false, ProductType.service);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
        Order savedOrder = mock(Order.class);
        UUID orderId = UUID.randomUUID();
        when(savedOrder.getId()).thenReturn(orderId);
        when(savedOrder.getOrderNumber()).thenReturn("WEB-FREE");
        when(savedOrder.getTrackingToken()).thenReturn("free-tracking");
        when(savedOrder.getTotal()).thenReturn(new BigDecimal("300.00"));
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

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("15")));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSubtotal()).isEqualByComparingTo("300.00");
        assertThat(orderCaptor.getValue().getShippingTotal()).isEqualByComparingTo("0.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("300.00");
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void checkoutAppliesTheQuantityPriceTierLikeThePos() {
        // 3 unidades caen en el escalón de Q15 (precio normal Q20).
        when(productPriceResolver.resolveEffectivePrice(
                        eq(tenantId), any(Product.class), any(), eq("ecommerce"), eq(branchId),
                        eq(new BigDecimal("3"))))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("15.00"), new BigDecimal("15.00"),
                        BigDecimal.ZERO.setScale(2), null));
        stubCustomShippingCheckout("WEB-TIER", "70.00");

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("3")));

        ArgumentCaptor<com.omniretail.backend.ecommerce.entity.OrderItem> itemCaptor =
                ArgumentCaptor.forClass(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        verify(orderItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getUnitPrice()).isEqualByComparingTo("15.00");
        assertThat(itemCaptor.getValue().getSubtotal()).isEqualByComparingTo("45.00");
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSubtotal()).isEqualByComparingTo("45.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("70.00");
    }

    @Test
    void checkoutChargesTierPriceWithTheDispatchBranchPromotion() {
        // Mismo escenario que PublicStorefrontCatalogServiceTest: el detalle publica Q72 desde 2.
        Product product = product(false, ProductType.service);
        ReflectionTestUtils.setField(
                service, "productPriceResolver", branchPromotionResolver(product, branchId));
        stubCustomShippingCheckout(product, "WEB-BRANCH-PROMO", "241.00");

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("3")));

        // 3 unidades: escalón Q80 con el 10 % de la sucursal de despacho = Q72 c/u.
        ArgumentCaptor<com.omniretail.backend.ecommerce.entity.OrderItem> itemCaptor =
                ArgumentCaptor.forClass(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        verify(orderItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getUnitPrice()).isEqualByComparingTo("80.00");
        assertThat(itemCaptor.getValue().getDiscount()).isEqualByComparingTo("24.00");
        assertThat(itemCaptor.getValue().getSubtotal()).isEqualByComparingTo("216.00");
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSubtotal()).isEqualByComparingTo("216.00");
    }

    /**
     * ProductPriceResolver real: escalón de Q80 desde 2 unidades (precio normal Q100) y una
     * promoción del 10 % en e-commerce válida solo en {@code branchId}.
     */
    private ProductPriceResolver branchPromotionResolver(Product product, UUID branchId) {
        ProductRepository products = mock(ProductRepository.class);
        PromotionRepository promotions = mock(PromotionRepository.class);
        ProductSalesPriceTierRepository tiers = mock(ProductSalesPriceTierRepository.class);
        lenient().when(product.getTenantId()).thenReturn(tenantId);
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("100.00"));
        when(tiers.findByTenantIdAndProductIdOrderByMinQuantityAsc(tenantId, productId))
                .thenReturn(List.of(ProductSalesPriceTier.builder()
                        .minQuantity(2).unitPrice(new BigDecimal("80.00")).active(true).build()));
        Promotion branchOnly = Promotion.builder()
                .name("10 % sucursal de despacho")
                .discountType(PromotionDiscountType.percentage)
                .discountValue(new BigDecimal("10"))
                .startsAt(Instant.parse("2026-01-01T00:00:00Z"))
                .status(PromotionStatus.active)
                .channels(List.of("ecommerce"))
                .branchIds(List.of(branchId))
                .createdByUserId(UUID.randomUUID())
                .build();
        branchOnly.setTenantId(tenantId);
        ReflectionTestUtils.setField(branchOnly, "id", UUID.randomUUID());
        when(promotions.findApplicable(eq(tenantId), eq(productId), eq(PromotionStatus.active), any()))
                .thenReturn(List.of(branchOnly));
        return new ProductPriceResolver(products, promotions, tiers);
    }

    @Test
    void checkoutChargesTheConfiguredFeeAboveTheOldDefaultThreshold() {
        useCustomEcommerceShipping();
        stubCustomShippingCheckout("WEB-CUSTOM", "340.00");

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("15")));

        // Con la tarifa por defecto Q300 ya sería gratis; la configurada cobra Q40 hasta Q500.
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSubtotal()).isEqualByComparingTo("300.00");
        assertThat(orderCaptor.getValue().getShippingTotal()).isEqualByComparingTo("40.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("340.00");
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("340.00");
    }

    @Test
    void checkoutOffersFreeShippingAtTheConfiguredThreshold() {
        useCustomEcommerceShipping();
        stubCustomShippingCheckout("WEB-CUSTOM-FREE", "500.00");

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("25")));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSubtotal()).isEqualByComparingTo("500.00");
        assertThat(orderCaptor.getValue().getShippingTotal()).isEqualByComparingTo("0.00");
        assertThat(orderCaptor.getValue().getTotal()).isEqualByComparingTo("500.00");
    }

    /** Tarifa del tenant distinta de la de por defecto: Q40, gratis desde Q500. */
    private void useCustomEcommerceShipping() {
        ShippingChannelConfigDto custom =
                new ShippingChannelConfigDto(true, new BigDecimal("40.00"), new BigDecimal("500.00"));
        when(shippingConfigService.calculate(eq(tenantId), eq(ShippingChannel.ecommerce), any()))
                .thenAnswer(invocation -> ShippingConfigService.calculate(custom, invocation.getArgument(2)));
    }

    private void stubCustomShippingCheckout(String orderNumber, String total) {
        stubCustomShippingCheckout(product(false, ProductType.service), orderNumber, total);
    }

    private void stubCustomShippingCheckout(Product product, String orderNumber, String total) {
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(product));
        Order savedOrder = mock(Order.class);
        UUID orderId = UUID.randomUUID();
        when(savedOrder.getId()).thenReturn(orderId);
        when(savedOrder.getOrderNumber()).thenReturn(orderNumber);
        when(savedOrder.getTrackingToken()).thenReturn("custom-tracking");
        when(savedOrder.getTotal()).thenReturn(new BigDecimal(total));
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
    }

    @Test
    void reservesEveryFixedKitComponentForEcommerceFulfillment() {
        UUID firstComponentId = UUID.randomUUID();
        UUID secondComponentId = UUID.randomUUID();
        Product kit = product(false, ProductType.kit);
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, productId, ProductStatus.published)).thenReturn(Optional.of(kit));
        when(productKitService.fulfillment(tenantId, kit, new BigDecimal("2")))
                .thenReturn(List.of(
                        new ProductKitService.FulfillmentComponent(
                                firstComponentId,
                                new BigDecimal("3.000"),
                                new BigDecimal("6.000")),
                        new ProductKitService.FulfillmentComponent(
                                secondComponentId,
                                new BigDecimal("1.000"),
                                new BigDecimal("2.000"))));
        Order savedOrder = mock(Order.class);
        UUID orderId = UUID.randomUUID();
        when(savedOrder.getId()).thenReturn(orderId);
        when(savedOrder.getOrderNumber()).thenReturn("WEB-KIT");
        when(savedOrder.getTrackingToken()).thenReturn("kit-tracking");
        when(savedOrder.getTotal()).thenReturn(new BigDecimal("40.00"));
        when(savedOrder.getStatus()).thenReturn(OrderStatus.confirmed);
        when(savedOrder.getTenantId()).thenReturn(tenantId);
        when(savedOrder.getDeliveryAddress()).thenReturn(
                "{\"recipientName\":\"Maria\",\"line1\":\"7a Avenida\","
                        + "\"line2\":null,\"city\":\"Guatemala\","
                        + "\"stateOrDepartment\":null,\"recipientPhone\":\"55551234\"}");
        when(orderRepository.save(any())).thenReturn(savedOrder);
        com.omniretail.backend.ecommerce.entity.OrderItem savedItem =
                mock(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        UUID orderItemId = UUID.randomUUID();
        when(savedItem.getId()).thenReturn(orderItemId);
        when(orderItemRepository.save(any())).thenReturn(savedItem);
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.approved);
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(reservationRepository.findByTenantIdAndOrderId(tenantId, orderId)).thenReturn(List.of());

        service.checkout("ferreteria", "checkout-1", request(new BigDecimal("2")));

        ArgumentCaptor<ReserveInventoryCommand> reservations =
                ArgumentCaptor.forClass(ReserveInventoryCommand.class);
        verify(reservationLifecycleService, times(2)).reserve(reservations.capture());
        assertThat(reservations.getAllValues()).extracting(ReserveInventoryCommand::productId)
                .containsExactlyInAnyOrder(firstComponentId, secondComponentId);
        assertThat(reservations.getAllValues()).extracting(ReserveInventoryCommand::quantity)
                .containsExactlyInAnyOrder(new BigDecimal("6.000"), new BigDecimal("2.000"));
        assertThat(reservations.getAllValues()).allSatisfy(reservation -> {
            assertThat(reservation.sourceType()).isEqualTo(InventoryReservationSourceType.order);
            assertThat(reservation.sourceId()).isEqualTo(orderId);
            assertThat(reservation.orderId()).isEqualTo(orderId);
            assertThat(reservation.orderItemId()).isEqualTo(orderItemId);
            assertThat(reservation.sourceLineId()).isEqualTo(orderItemId);
        });
        verify(pickingService).ensureForOrder(tenantId, orderId);
        verify(orderItemRepository).save(org.mockito.ArgumentMatchers.argThat(item ->
                item.getProductId().equals(productId)
                        && item.getFulfillmentComponents().contains(firstComponentId.toString())
                        && item.getFulfillmentComponents().contains(secondComponentId.toString())));
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
        Order savedOrder = mock(Order.class);
        when(savedOrder.getId()).thenReturn(UUID.randomUUID());
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        com.omniretail.backend.ecommerce.entity.OrderItem savedItem =
                mock(com.omniretail.backend.ecommerce.entity.OrderItem.class);
        when(savedItem.getInventoryQuantity()).thenReturn(BigDecimal.ONE);
        when(orderItemRepository.save(any())).thenReturn(savedItem);
        when(reservationLifecycleService.reserve(any(ReserveInventoryCommand.class)))
                .thenThrow(BusinessException.conflict("INSUFFICIENT_STOCK", "Stock insuficiente."));

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
            verify(reservationLifecycleService, never()).reserve(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Product product(boolean trackingStock, ProductType type) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getSku()).thenReturn("SKU-1");
        when(product.getName()).thenReturn("Martillo");
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("20.00"));
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
