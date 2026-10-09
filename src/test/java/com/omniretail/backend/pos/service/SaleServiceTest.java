package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.repository.BusinessCapabilitiesConfigRepository;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.catalog.service.ProductKitService;
import com.omniretail.backend.catalog.service.ProductUnitConversionResolver;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.entity.TransportMode;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.InventoryOutboundCommand;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityHistoryService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.InventoryTrackingSelectionRequest;
import com.omniretail.backend.pos.dto.VoidSaleRequest;
import com.omniretail.backend.pos.dto.VoidSaleResponse;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleDocumentType;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.entity.SaleReversalOperation;
import com.omniretail.backend.pos.entity.SaleReversalOperationType;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.pos.repository.SaleReversalOperationRepository;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import com.omniretail.backend.administration.entity.UserType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SaleServiceTest {
    @Mock CurrentUser currentUser;
    @Mock TenantCapabilityGuard capability;
    @Mock BranchAccessResolver branchAccess;
    @Mock CashShiftRepository shifts;
    @Mock ProductRepository products;
    @Mock TenantRepository tenants;
    @Mock CustomerRepository customers;
    @Mock BusinessCapabilitiesConfigRepository businessConfig;
    @Mock BankAccountRepository bankAccounts;
    @Mock SaleRepository sales;
    @Mock SaleReversalOperationRepository reversalOperations;
    @Mock SaleItemRepository items;
    @Mock PaymentRepository payments;
    @Mock CashMovementRepository cashMovements;
    @Mock InventoryStockService inventory;
    @Mock InventoryTraceabilityMutationService traceabilityMutation;
    @Mock InventoryTraceabilityHistoryService traceabilityHistory;
    @Mock InventoryMovementRepository inventoryMovements;
    @Mock DocumentCounterService counter;
    @Mock ProductPriceResolver productPriceResolver;
    @Mock ProductKitService productKitService;
    @Mock ProductUnitConversionResolver unitConversionResolver;
    @Mock OrderRepository orders;
    @Mock OrderItemRepository orderItems;
    @Mock InventoryReservationRepository reservations;
    @Mock InventoryReservationLifecycleService reservationLifecycle;
    @Mock PickingService pickingService;
    @Mock PickingOrderRepository pickingOrders;
    @Mock JsonMapper jsonMapper;
    @Mock com.omniretail.backend.ecommerce.service.OrderEmailNotifier orderEmailNotifier;
    @InjectMocks SaleService service;

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID branch = UUID.randomUUID();
    private final UUID shiftId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private AuthenticatedUser actor;

    @BeforeEach
    void setUp() {
        actor = new AuthenticatedUser(user, tenant, UserType.employee, UUID.randomUUID(), branch, UUID.randomUUID());
        when(currentUser.require()).thenReturn(actor);
        when(branchAccess.resolve(actor)).thenReturn(new BranchAccessResolver.BranchAccess(false, Set.of(branch)));
        lenient().when(shifts.findOwnedByIdForUpdate(tenant, user, shiftId)).thenReturn(Optional.of(shift()));
        lenient().when(counter.nextPosSaleNumber(tenant)).thenReturn("POS-001");
        lenient().when(tenants.findById(tenant)).thenReturn(Optional.of(Tenant.builder()
                .name("Tenant").slug("tenant").status(TenantStatus.active).defaultCurrency("GTQ")
                .timezone("America/Guatemala").build()));
        lenient().when(businessConfig.findByTenantId(tenant)).thenReturn(Optional.empty());
        lenient().when(productPriceResolver.resolveEffectivePrice(
                        eq(tenant), any(Product.class), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"),
                        new BigDecimal("20.00"),
                        BigDecimal.ZERO.setScale(2),
                        null));
        lenient().when(unitConversionResolver.toBaseQuantity(
                        eq(tenant), any(Product.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(items.saveAndFlush(any())).thenAnswer(invocation -> {
            SaleItem value = invocation.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
            return value;
        });
        lenient().when(orderItems.saveAndFlush(any())).thenAnswer(invocation -> {
            OrderItem value = invocation.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
            return value;
        });
        lenient().when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        lenient().when(orders.findByTenantIdAndSourceAndIdempotencyKey(
                eq(tenant), eq(OrderSource.pos), any())).thenReturn(Optional.empty());
        lenient().when(inventory.deductStock(any())).thenAnswer(invocation -> {
            DeductStockCommand command = invocation.getArgument(0);
            InventoryMovement movement = InventoryMovement.builder()
                    .tenantId(command.tenantId())
                    .branchId(command.branchId())
                    .productId(command.productId())
                    .type(InventoryMovementType.out)
                    .reason(command.reason())
                    .quantity(command.qty())
                    .referenceType(command.referenceType())
                    .referenceId(command.referenceId())
                    .referenceLineId(command.referenceLineId())
                    .performedByUserId(command.performedByUserId())
                    .build();
            ReflectionTestUtils.setField(movement, "id", UUID.randomUUID());
            return movement;
        });
        lenient().when(traceabilityMutation.consume(any())).thenAnswer(invocation -> {
            InventoryOutboundCommand command = invocation.getArgument(0);
            InventoryMovement movement = InventoryMovement.builder()
                    .tenantId(command.tenantId())
                    .branchId(command.branchId())
                    .productId(command.product().getId())
                    .type(InventoryMovementType.out)
                    .reason(command.reason())
                    .quantity(command.baseQuantity())
                    .fromLocationId(command.locationId())
                    .referenceType(command.referenceType())
                    .referenceId(command.referenceId())
                    .referenceLineId(command.referenceLineId())
                    .performedByUserId(command.actorUserId())
                    .build();
            ReflectionTestUtils.setField(movement, "id", UUID.randomUUID());
            return movement;
        });
        lenient().when(traceabilityHistory.expand(any(), any())).thenReturn(java.util.Map.of());
    }

    @Test
    void createsSaleAndDeductsStock() {
        Product product = product();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        when(sales.saveAndFlush(any())).thenAnswer(invocation -> {
            var sale = invocation.getArgument(0, com.omniretail.backend.pos.entity.Sale.class);
            ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
            return sale;
        });
        var result = service.create(request(new BigDecimal("40.00"), new BigDecimal("2")));
        assertThat(result.number()).isEqualTo("POS-001");
        assertThat(result.subtotal()).isEqualByComparingTo("40.00");
        assertThat(result.total()).isEqualByComparingTo("40.00");
        verify(inventory).deductStock(any());
        verify(items).saveAndFlush(any());
        verify(payments).save(any());
        verify(cashMovements).save(any());
    }

    @Test
    void defaultsToTicketAndPersistsNoFiscalData() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        stubSalePersistence();

        var result = service.create(request(new BigDecimal("20.00"), BigDecimal.ONE));

        assertThat(result.document().type()).isEqualTo(SaleDocumentType.ticket);
        verify(sales).saveAndFlush(argThat(sale -> sale.getDocumentType() == SaleDocumentType.ticket
                && sale.getDocumentTaxId() == null
                && sale.getDocumentLegalName() == null
                && sale.getDocumentFiscalAddress() == null));
    }

    @Test
    void normalizesAndPersistsInvoiceSnapshot() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        stubSalePersistence();
        CreateSaleRequest base = request(new BigDecimal("20.00"), BigDecimal.ONE);
        CreateSaleRequest request = new CreateSaleRequest(
                base.branchId(), base.cashShiftId(), base.customerId(), base.taxTotal(),
                base.items(), base.payments(), base.confirmationId(),
                new CreateSaleRequest.Document(
                        SaleDocumentType.invoice, " 1234567-8 ", " Empresa, S.A. ", " Zona 1 "));

        var result = service.create(request);

        assertThat(result.document().taxId()).isEqualTo("1234567-8");
        assertThat(result.document().legalName()).isEqualTo("Empresa, S.A.");
        assertThat(result.document().fiscalAddress()).isEqualTo("Zona 1");
    }

    @Test
    void rejectsIncompleteInvoiceBeforeCreatingEffects() {
        reset(branchAccess);
        CreateSaleRequest base = request(new BigDecimal("20.00"), BigDecimal.ONE);
        CreateSaleRequest request = new CreateSaleRequest(
                base.branchId(), base.cashShiftId(), base.customerId(), base.taxTotal(),
                base.items(), base.payments(), base.confirmationId(),
                new CreateSaleRequest.Document(SaleDocumentType.invoice, "123", "Empresa", " "));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVOICE_FISCAL_DATA_REQUIRED"));
        verifyNoInteractions(sales, inventory, payments, cashMovements);
        verifyNoInteractions(branchAccess);
    }

    @Test
    void rejectsTicketWithFiscalData() {
        reset(branchAccess);
        CreateSaleRequest base = request(new BigDecimal("20.00"), BigDecimal.ONE);
        CreateSaleRequest request = new CreateSaleRequest(
                base.branchId(), base.cashShiftId(), base.customerId(), base.taxTotal(),
                base.items(), base.payments(), base.confirmationId(),
                new CreateSaleRequest.Document(SaleDocumentType.ticket, "123", null, null));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("TICKET_FISCAL_DATA_NOT_ALLOWED"));
        verifyNoInteractions(branchAccess, sales, inventory, payments, cashMovements);
    }

    @Test
    void deductsServerDerivedPhysicalQuantityAndKeepsCommercialSaleQuantity() {
        Product product = product();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        when(unitConversionResolver.toBaseQuantity(tenant, product, new BigDecimal("2")))
                .thenReturn(new BigDecimal("24.000"));
        stubSalePersistence();

        service.create(request(new BigDecimal("40.00"), new BigDecimal("2")));

        verify(inventory).deductStock(argThat(command ->
                command.qty().compareTo(new BigDecimal("24.000")) == 0));
        verify(items).saveAndFlush(argThat(item -> item.getQuantity().compareTo(new BigDecimal("2")) == 0));
    }

    @Test
    void rejectsMissingConversionBeforePersistingAnySaleEffect() {
        Product product = product();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        when(unitConversionResolver.toBaseQuantity(tenant, product, BigDecimal.ONE))
                .thenThrow(new BusinessException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "UNIT_CONVERSION_REQUIRED",
                        "Conversión requerida."));

        assertThatThrownBy(() -> service.create(request(new BigDecimal("20.00"), BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("UNIT_CONVERSION_REQUIRED"));
        verify(sales, never()).saveAndFlush(any());
        verifyNoInteractions(inventory, payments, cashMovements, counter);
    }

    @Test
    void kitSaleDeductsComponentsAndStoresFulfillmentSnapshot() {
        UUID componentId = UUID.randomUUID();
        Product kit = product();
        kit.setProductType(ProductType.kit);
        kit.setTrackingStock(false);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(kit));
        when(productKitService.fulfillment(eq(tenant), eq(kit), eq(new BigDecimal("2"))))
                .thenReturn(List.of(new ProductKitService.FulfillmentComponent(
                        componentId, new BigDecimal("3.000"), new BigDecimal("6.000"))));
        Product component = product();
        ReflectionTestUtils.setField(component, "id", componentId);
        when(products.findByTenantIdAndId(tenant, componentId)).thenReturn(Optional.of(component));
        stubSalePersistence();

        service.create(request(new BigDecimal("40.00"), new BigDecimal("2")));

        verify(inventory).deductStock(argThat(command -> command.productId().equals(componentId)
                && command.qty().compareTo(new BigDecimal("6.000")) == 0));
        verify(items).saveAndFlush(argThat(item -> item.getProductId().equals(productId)
                && item.getFulfillmentComponents().contains(componentId.toString())));
    }

    @Test
    void traceableKitComponentRequiresAndUsesItsExplicitSelection() {
        UUID componentId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        Product kit = product();
        kit.setProductType(ProductType.kit);
        kit.setTrackingStock(false);
        Product component = product();
        ReflectionTestUtils.setField(component, "id", componentId);
        component.setTrackingLot(true);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(kit));
        when(products.findByTenantIdAndId(tenant, componentId)).thenReturn(Optional.of(component));
        when(productKitService.fulfillment(eq(tenant), eq(kit), eq(new BigDecimal("2"))))
                .thenReturn(List.of(new ProductKitService.FulfillmentComponent(
                        componentId, new BigDecimal("3.000"), new BigDecimal("6.000"))));
        stubSalePersistence();
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(
                        productId,
                        new BigDecimal("2"),
                        BigDecimal.ZERO,
                        List.of(new InventoryTrackingSelectionRequest(
                                componentId,
                                locationId,
                                lotId,
                                new BigDecimal("6.000"),
                                List.of())))),
                List.of(new CreateSaleRequest.PaymentLine(
                        PaymentMethod.cash, new BigDecimal("40.00"), null)),
                UUID.randomUUID());

        service.create(request);

        verify(traceabilityMutation).consume(argThat(command ->
                command.product().getId().equals(componentId)
                        && command.baseQuantity().compareTo(new BigDecimal("6.000")) == 0
                        && command.locationId().equals(locationId)
                        && command.referenceType().equals("POS_KIT_SALE")
                        && command.referenceLineId() != null));
        verifyNoInteractions(inventory);
    }

    @Test
    void traceableKitComponentCannotUseLegacyStockPathWithoutSelection() {
        UUID componentId = UUID.randomUUID();
        Product kit = product();
        kit.setProductType(ProductType.kit);
        kit.setTrackingStock(false);
        Product component = product();
        ReflectionTestUtils.setField(component, "id", componentId);
        component.setTrackingSerial(true);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(kit));
        when(products.findByTenantIdAndId(tenant, componentId)).thenReturn(Optional.of(component));
        when(productKitService.fulfillment(eq(tenant), eq(kit), eq(new BigDecimal("2"))))
                .thenReturn(List.of(new ProductKitService.FulfillmentComponent(
                        componentId, new BigDecimal("3.000"), new BigDecimal("6.000"))));
        stubSalePersistence();

        assertThatThrownBy(() -> service.create(
                        request(new BigDecimal("40.00"), new BigDecimal("2"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("TRACKING_SELECTIONS_REQUIRED"));
        verifyNoInteractions(inventory, traceabilityMutation);
    }

    @Test
    void voidRestoresKitComponentsFromSnapshot() {
        UUID componentId = UUID.randomUUID();
        Sale sale = Sale.builder().branchId(branch).cashShiftId(shiftId).number("POS-1")
                .status(SaleStatus.completed).subtotal(new BigDecimal("20.00"))
                .discountTotal(BigDecimal.ZERO).taxTotal(BigDecimal.ZERO).total(new BigDecimal("20.00"))
                .createdByUserId(user).build();
        sale.setTenantId(tenant);
        ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
        SaleItem item = SaleItem.builder().saleId(sale.getId()).productId(productId).skuSnapshot("KIT")
                .nameSnapshot("Kit").quantity(new BigDecimal("2.000")).unitPrice(new BigDecimal("10.00"))
                .discount(BigDecimal.ZERO).subtotal(new BigDecimal("20.00"))
                .fulfillmentComponents("[{\"productId\":\"" + componentId
                        + "\",\"quantityPerKit\":3.000}]").build();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(items.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(item));
        Product component = product();
        ReflectionTestUtils.setField(component, "id", componentId);
        when(products.findByTenantIdAndId(tenant, componentId)).thenReturn(Optional.of(component));
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.voidSale(sale.getId());

        verify(inventory).incrementStock(argThat(command -> command.productId().equals(componentId)
                && command.qty().compareTo(new BigDecimal("6.000")) == 0));
    }

    @Test
    void promotionWinsAgainstZeroManualDiscountAndIsSnapshotted() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("15.00"), BigDecimal.ONE, BigDecimal.ZERO));

        ArgumentCaptor<SaleItem> saved = ArgumentCaptor.forClass(SaleItem.class);
        verify(items).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDiscount()).isEqualByComparingTo("5.00");
        assertThat(saved.getValue().getSubtotal()).isEqualByComparingTo("15.00");
        assertThat(saved.getValue().getPromotionId()).isEqualTo(promotionId);
        assertThat(saved.getValue().getUnitPrice()).isEqualByComparingTo("20.00");
    }

    @Test
    void greaterManualDiscountWinsAndClearsPromotionSnapshot() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("13.00"), BigDecimal.ONE, new BigDecimal("7.00")));

        ArgumentCaptor<SaleItem> saved = ArgumentCaptor.forClass(SaleItem.class);
        verify(items).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDiscount()).isEqualByComparingTo("7.00");
        assertThat(saved.getValue().getPromotionId()).isNull();
    }

    @Test
    void equalDiscountDeterministicallyPrefersPromotion() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("15.00"), BigDecimal.ONE, new BigDecimal("5.00")));

        verify(items).saveAndFlush(argThat(item -> promotionId.equals(item.getPromotionId())));
    }

    @Test
    void comparesPromotionAndManualDiscountAtLineQuantity() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("30.00"), new BigDecimal("2"), new BigDecimal("7.00")));

        verify(items).saveAndFlush(argThat(item ->
                promotionId.equals(item.getPromotionId())
                        && item.getDiscount().compareTo(new BigDecimal("10.00")) == 0
                        && item.getSubtotal().compareTo(new BigDecimal("30.00")) == 0));
    }

    @Test
    void appliedDiscountIsCappedAtLineGross() {
        UUID promotionId = UUID.randomUUID();
        UUID additionalProductId = UUID.randomUUID();
        Product discountedProduct = product();
        Product additionalProduct = Product.builder()
                .sku("SKU-2")
                .name("Producto adicional")
                .salePrice(new BigDecimal("1.00"))
                .channelPos(true)
                .build();
        ReflectionTestUtils.setField(additionalProduct, "id", additionalProductId);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(discountedProduct));
        when(products.findByTenantIdAndId(tenant, additionalProductId)).thenReturn(Optional.of(additionalProduct));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), eq(discountedProduct), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("20.00"), promotionId));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), eq(additionalProduct), any(Instant.class), eq("pos"), eq(branch), any(BigDecimal.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("1.00"), new BigDecimal("1.00"),
                        BigDecimal.ZERO.setScale(2), null));
        stubSalePersistence();

        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(
                        new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO),
                        new CreateSaleRequest.Item(additionalProductId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(
                        PaymentMethod.cash, new BigDecimal("1.00"), null)),
                UUID.randomUUID());

        var response = service.create(request);

        assertThat(response.total()).isEqualByComparingTo("1.00");
        verify(items).saveAndFlush(argThat(item ->
                productId.equals(item.getProductId())
                        && item.getDiscount().compareTo(new BigDecimal("20.00")) == 0
                        && item.getSubtotal().compareTo(BigDecimal.ZERO) == 0
                        && promotionId.equals(item.getPromotionId())));
    }

    @Test
    void rejectsPaymentsThatDoNotMatchTotal() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        CreateSaleRequest request = request(new BigDecimal("19.99"), BigDecimal.ONE);
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_TOTAL_MISMATCH"));
        verify(sales).findByTenantIdAndConfirmationId(tenant, request.confirmationId());
        verify(sales, never()).save(any());
        verify(sales, never()).saveAndFlush(any());
        verifyNoInteractions(inventory, items, payments, cashMovements, counter);
    }

    @Test
    void calculatesDiscountAndTaxOnTheServer() {
        Product product = product();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        when(sales.saveAndFlush(any())).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
            return sale;
        });

        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                new BigDecimal("99.99"),
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                UUID.randomUUID());

        var response = service.create(request);

        assertThat(response.discountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.taxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    void rejectsSaleWithoutConfirmationId() {
        reset(branchAccess);
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CONFIRMATION_ID_REQUIRED"));
    }

    @Test
    void rejectsDuplicateProducts() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(
                        new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO),
                        new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("40.00"), null)),
                UUID.randomUUID());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("DUPLICATE_PRODUCT"));
    }

    @Test
    void rejectsZeroAmountPayment() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.create(request(BigDecimal.ZERO, BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_AMOUNT_INVALID"));
    }

    @Test
    void rejectsUnknownCustomer() {
        UUID customerId = UUID.randomUUID();
        when(customers.findByTenantIdAndId(tenant, customerId)).thenReturn(Optional.empty());
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                customerId,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                UUID.randomUUID());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void storesTransferDataSeparately() {
        UUID bankAccountId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(bankAccounts.findByTenantIdAndId(tenant, bankAccountId)).thenReturn(Optional.of(
                com.omniretail.backend.administration.entity.BankAccount.builder()
                        .status(com.omniretail.backend.administration.entity.BankAccountStatus.active)
                        .branchIds(List.of(branch))
                        .build()));
        when(sales.saveAndFlush(any())).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
            return sale;
        });
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(
                        PaymentMethod.transfer,
                        new BigDecimal("20.00"),
                        bankAccountId,
                        "COMPROBANTE-001",
                        true)),
                UUID.randomUUID());

        service.create(request);

        verify(payments).save(argThat(payment -> bankAccountId.equals(payment.getBankAccountId())
                && "COMPROBANTE-001".equals(payment.getReference())
                && Boolean.TRUE.equals(payment.getExternallyVerified())
                && user.equals(payment.getVerifiedByUserId())
                && payment.getVerifiedAt() != null));
    }

    @Test
    void persistsMixedTenderAsConcretePaymentsAndOnlyCashAffectsDrawer() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        stubSalePersistence();
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(
                        new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("5.00"), null),
                        new CreateSaleRequest.PaymentLine(PaymentMethod.card, new BigDecimal("15.00"), "CARD-1")),
                UUID.randomUUID());

        service.create(request);

        verify(payments, times(2)).save(any());
        verify(cashMovements).save(argThat(movement ->
                movement.getAmount().compareTo(new BigDecimal("5.00")) == 0));
    }

    @Test
    void returnsExistingSaleForRepeatedConfirmation() {
        UUID confirmationId = UUID.randomUUID();
        Sale existing = sale(SaleStatus.completed);
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                confirmationId);
        ReflectionTestUtils.setField(existing, "confirmationFingerprint",
                ReflectionTestUtils.invokeMethod(service, "fingerprint", request));
        when(sales.findByTenantIdAndConfirmationId(tenant, confirmationId)).thenReturn(Optional.of(existing));
        when(items.findByTenantIdAndSaleId(tenant, existing.getId())).thenReturn(List.of(saleItem()));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, existing.getId()))
                .thenReturn(List.of(Payment.builder()
                        .method(PaymentMethod.cash).amount(new BigDecimal("20.00")).build()));
        when(inventoryMovements.findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, List.of("POS_SALE", "POS_KIT_SALE"), existing.getId()))
                .thenReturn(List.of(InventoryMovement.builder()
                        .tenantId(tenant).branchId(branch).productId(productId)
                        .type(InventoryMovementType.out).reason("Venta")
                        .quantity(BigDecimal.ONE).referenceType("POS_SALE")
                        .referenceId(existing.getId()).build()));
        when(cashMovements.findFirstByTenantIdAndReferenceTypeAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, "sale", existing.getId()))
                .thenReturn(Optional.of(CashMovement.builder()
                        .tenantId(tenant).cashShiftId(shiftId).type(CashMovementType.in)
                        .amount(new BigDecimal("20.00")).reason("Venta")
                        .referenceType("sale").referenceId(existing.getId())
                        .createdByUserId(user).build()));

        var result = service.create(request);

        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(result.idempotent()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.payments()).hasSize(1);
        assertThat(result.inventoryEffects()).hasSize(1);
        assertThat(result.cashMovement()).isNotNull();
        verify(inventory, never()).deductStock(any());
        verify(items, never()).save(any());
        verify(payments, never()).save(any());
        verify(cashMovements, never()).save(any());
        verifyNoInteractions(counter);
        verifyNoInteractions(productPriceResolver);
    }

    @Test
    void rejectsReusedConfirmationWhenNormalizedDocumentDiffers() {
        UUID confirmationId = UUID.randomUUID();
        CreateSaleRequest original = new CreateSaleRequest(
                branch, shiftId, null, BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                confirmationId,
                new CreateSaleRequest.Document(SaleDocumentType.invoice, "123", "Empresa", "Zona 1"));
        Sale existing = sale(SaleStatus.completed);
        ReflectionTestUtils.setField(existing, "confirmationFingerprint",
                ReflectionTestUtils.invokeMethod(service, "fingerprint", original));
        when(sales.findByTenantIdAndConfirmationId(tenant, confirmationId)).thenReturn(Optional.of(existing));
        CreateSaleRequest changed = new CreateSaleRequest(
                branch, shiftId, null, BigDecimal.ZERO, original.items(), original.payments(), confirmationId,
                new CreateSaleRequest.Document(SaleDocumentType.invoice, "456", "Empresa", "Zona 1"));

        assertThatThrownBy(() -> service.create(changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsReusedConfirmationWithDifferentPayload() {
        UUID confirmationId = UUID.randomUUID();
        Sale existing = sale(SaleStatus.completed);
        ReflectionTestUtils.setField(existing, "confirmationFingerprint", "distinct-fingerprint");
        when(sales.findByTenantIdAndConfirmationId(tenant, confirmationId)).thenReturn(Optional.of(existing));
        CreateSaleRequest request = new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                confirmationId);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsPaymentMethodNotAllowedByTenantConfiguration() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(businessConfig.findByTenantId(tenant)).thenReturn(Optional.of(
                com.omniretail.backend.administration.entity.BusinessCapabilitiesConfig.builder()
                        .allowedPosPaymentMethods(List.of("cash"))
                        .build()));

        assertThatThrownBy(() -> service.create(request(PaymentMethod.card, "CARD-001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_NOT_ALLOWED"));
    }

    @Test
    void rejectsCardPaymentWithoutReference() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.create(request(PaymentMethod.card, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_REFERENCE_REQUIRED"));
    }

    @Test
    void rejectsTransferWithInvalidBankAccount() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.create(request(PaymentMethod.transfer, "not-a-uuid")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BANK_ACCOUNT_INVALID"));
    }

    @Test
    void rejectsProductUnavailableForPos() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.empty());
        CreateSaleRequest request = request(new BigDecimal("20.00"), BigDecimal.ONE);
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
        verify(sales).findByTenantIdAndConfirmationId(tenant, request.confirmationId());
        verify(sales, never()).save(any());
        verify(sales, never()).saveAndFlush(any());
        verifyNoInteractions(inventory, items, payments, cashMovements, counter);
    }

    @Test
    void rejectsShiftNotOwnedByCashier() {
        when(shifts.findOwnedByIdForUpdate(tenant, user, shiftId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request(new BigDecimal("20.00"), BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CASH_SHIFT_NOT_FOUND"));
        verifyNoInteractions(products, sales, inventory, items, payments);
    }

    @Test
    void createsTraceableDeferredHomeDeliveryWithReservationAndPickingWithoutStockOut() {
        Product product = product();
        product.setTrackingLot(true);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        when(unitConversionResolver.toBaseQuantity(tenant, product, new BigDecimal("2")))
                .thenReturn(new BigDecimal("24.000"));
        stubSalePersistence();
        Order order = stubDeferredOrderPersistence();
        PickingOrder picking = picking(order);
        when(pickingService.ensureForOrder(tenant, order.getId())).thenReturn(Optional.of(picking));

        var result = service.create(deferredRequest(
                new BigDecimal("40.00"), new BigDecimal("2"), UUID.randomUUID()));

        assertThat(result.sourceOrderId()).isEqualTo(order.getId());
        assertThat(result.order().id()).isEqualTo(order.getId());
        assertThat(result.pickingOrder().id()).isEqualTo(picking.getId());
        verify(jsonMapper).writeValueAsString(argThat(value ->
                value instanceof Map<?, ?> guest
                        && "Cliente".equals(guest.get("name"))
                        && "cliente@example.com".equals(guest.get("email"))));
        verify(orderItems).saveAndFlush(argThat(item ->
                item.getQuantity().compareTo(new BigDecimal("2")) == 0
                        && item.getInventoryQuantity().compareTo(new BigDecimal("24.000")) == 0
                        && item.getUnitPrice().compareTo(new BigDecimal("20.00")) == 0));
        verify(reservationLifecycle).reserve(argThat(command ->
                command.sourceType() == com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType.order
                        && command.sourceId().equals(order.getId())
                        && command.sourceLineId().equals(command.orderItemId())
                        && command.orderId().equals(order.getId())
                        && command.quantity().compareTo(new BigDecimal("24.000")) == 0));
        verify(payments).save(argThat(payment ->
                payment.getSaleId() != null && order.getId().equals(payment.getOrderId())));
        verify(cashMovements, times(1)).save(argThat(movement ->
                movement.getType() == CashMovementType.in));
        verifyNoInteractions(inventory, traceabilityMutation);
    }

    @Test
    void createsTraceableDeferredStorePickupWithoutAddressOrStockOut() {
        Product product = product();
        product.setTrackingSerial(true);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        stubSalePersistence();
        Order order = stubDeferredOrderPersistence();
        PickingOrder picking = picking(order);
        when(pickingService.ensureForOrder(tenant, order.getId())).thenReturn(Optional.of(picking));
        CreateSaleRequest valid = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest storePickup = new CreateSaleRequest(
                valid.branchId(), valid.cashShiftId(), valid.customerId(), valid.taxTotal(),
                valid.items(), valid.payments(), valid.confirmationId(), valid.document(), null,
                new CreateSaleRequest.DeferredOrder(
                        valid.deferredOrder().idempotencyKey(),
                        DeliveryMethod.store_pickup,
                        TransportMode.customer,
                        null,
                        new CreateSaleRequest.NotificationContact("not_applicable", null),
                        new CreateSaleRequest.StorePickupContact(
                                " Cliente Retira ", "5555-5555")));

        var result = service.create(storePickup);

        assertThat(result.order().deliveryMethod()).isEqualTo(DeliveryMethod.store_pickup);
        assertThat(result.order().deliveryAddress()).isNull();
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStorePickupContact()).isNotNull();
        verify(jsonMapper).writeValueAsString(argThat(value ->
                value instanceof Map<?, ?> guest
                        && "Cliente Retira".equals(guest.get("name"))
                        && !guest.containsKey("email")));
        assertThat(result.sourceOrderId()).isEqualTo(order.getId());
        assertThat(result.pickingOrder().id()).isEqualTo(picking.getId());
        verify(reservationLifecycle).reserve(any());
        verifyNoInteractions(inventory, traceabilityMutation);
        verifyNoInteractions(inventoryMovements);
    }

    @Test
    void deferredSaleWithRegisteredCustomerDoesNotCreateGuestSnapshot() {
        UUID customerId = UUID.randomUUID();
        Customer customer = Customer.builder()
                .code("C-001")
                .name("Cliente registrado")
                .email("cliente@example.com")
                .build();
        customer.setTenantId(tenant);
        when(customers.findByTenantIdAndId(tenant, customerId)).thenReturn(Optional.of(customer));
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        stubSalePersistence();
        Order order = stubDeferredOrderPersistence();
        when(pickingService.ensureForOrder(tenant, order.getId()))
                .thenReturn(Optional.of(picking(order)));
        CreateSaleRequest base = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest request = new CreateSaleRequest(
                base.branchId(), base.cashShiftId(), customerId, base.taxTotal(),
                base.items(), base.payments(), base.confirmationId(), base.document(),
                base.sourceOrderId(), base.deferredOrder());

        service.create(request);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getCustomerId()).isEqualTo(customerId);
        assertThat(orderCaptor.getValue().getGuestCustomer()).isNull();
    }

    @Test
    void rejectsMissingStorePickupContact() {
        reset(branchAccess);
        CreateSaleRequest valid = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest request = new CreateSaleRequest(
                valid.branchId(), valid.cashShiftId(), valid.customerId(), valid.taxTotal(),
                valid.items(), valid.payments(), valid.confirmationId(), valid.document(), null,
                new CreateSaleRequest.DeferredOrder(
                        valid.deferredOrder().idempotencyKey(),
                        DeliveryMethod.store_pickup,
                        TransportMode.customer,
                        null,
                        valid.deferredOrder().notificationContact(),
                        null));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("STORE_PICKUP_CONTACT_REQUIRED"));
    }

    @Test
    void rejectsInvalidStorePickupPhone() {
        reset(branchAccess);
        CreateSaleRequest valid = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest request = new CreateSaleRequest(
                valid.branchId(), valid.cashShiftId(), valid.customerId(), valid.taxTotal(),
                valid.items(), valid.payments(), valid.confirmationId(), valid.document(), null,
                new CreateSaleRequest.DeferredOrder(
                        valid.deferredOrder().idempotencyKey(),
                        DeliveryMethod.store_pickup,
                        TransportMode.customer,
                        null,
                        valid.deferredOrder().notificationContact(),
                        new CreateSaleRequest.StorePickupContact("Cliente", "123")));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("STORE_PICKUP_PHONE_INVALID"));
    }

    @Test
    void rejectsPhysicalTrackingSelectionInDeferredPosRequest() {
        Product traceable = product();
        traceable.setTrackingLot(true);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(traceable));
        CreateSaleRequest valid = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest withSelection = new CreateSaleRequest(
                valid.branchId(),
                valid.cashShiftId(),
                valid.customerId(),
                valid.taxTotal(),
                List.of(new CreateSaleRequest.Item(
                        productId,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        List.of(new InventoryTrackingSelectionRequest(
                                productId,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                BigDecimal.ONE,
                                List.of())))),
                valid.payments(),
                valid.confirmationId(),
                valid.document(),
                null,
                valid.deferredOrder());

        assertThatThrownBy(() -> service.create(withSelection))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("INVALID_TRACKING_SELECTION"));
        verifyNoInteractions(orders, orderItems, reservationLifecycle, pickingService);
    }

    @Test
    void rejectsConflictingSourceOrderInput() {
        reset(branchAccess);
        CreateSaleRequest valid = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());

        CreateSaleRequest conflicting = new CreateSaleRequest(
                valid.branchId(), valid.cashShiftId(), valid.customerId(), valid.taxTotal(),
                valid.items(), valid.payments(), valid.confirmationId(), valid.document(),
                UUID.randomUUID(), valid.deferredOrder());
        assertThatThrownBy(() -> service.create(conflicting))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("SALE_ORDER_INPUT_CONFLICT"));
        verifyNoInteractions(products, orders, sales, reservationLifecycle, pickingService);
    }

    @Test
    void rejectsImmediateAsDeferredDeliveryMethod() {
        reset(branchAccess);
        CreateSaleRequest valid = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest unsupported = new CreateSaleRequest(
                valid.branchId(), valid.cashShiftId(), valid.customerId(), valid.taxTotal(),
                valid.items(), valid.payments(), valid.confirmationId(), valid.document(), null,
                new CreateSaleRequest.DeferredOrder(
                        valid.deferredOrder().idempotencyKey(),
                        DeliveryMethod.immediate,
                        TransportMode.none,
                        null,
                        valid.deferredOrder().notificationContact()));

        assertThatThrownBy(() -> service.create(unsupported))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("DEFERRED_DELIVERY_METHOD_NOT_SUPPORTED"));

        verifyNoInteractions(products, orders, sales, reservationLifecycle, pickingService);
    }

    @Test
    void deferredFingerprintDistinguishesHomeDeliveryAndStorePickup() {
        reset(currentUser, branchAccess);
        CreateSaleRequest home = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID());
        CreateSaleRequest pickup = new CreateSaleRequest(
                home.branchId(), home.cashShiftId(), home.customerId(), home.taxTotal(),
                home.items(), home.payments(), home.confirmationId(), home.document(), null,
                new CreateSaleRequest.DeferredOrder(
                        home.deferredOrder().idempotencyKey(),
                        DeliveryMethod.store_pickup,
                        TransportMode.customer,
                        null,
                        home.deferredOrder().notificationContact(),
                        new CreateSaleRequest.StorePickupContact("Cliente", "5555-5555")));

        String homeFingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", home);
        String pickupFingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", pickup);

        assertThat(pickupFingerprint).isNotEqualTo(homeFingerprint);
    }

    @Test
    void replayOfDeferredConfirmationReturnsSameOrderAndPickingWithoutNewEffects() {
        Product product = product();
        UUID confirmationId = UUID.randomUUID();
        CreateSaleRequest homeRequest = deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, confirmationId);
        CreateSaleRequest request = new CreateSaleRequest(
                homeRequest.branchId(), homeRequest.cashShiftId(), homeRequest.customerId(),
                homeRequest.taxTotal(), homeRequest.items(), homeRequest.payments(),
                homeRequest.confirmationId(), homeRequest.document(), null,
                new CreateSaleRequest.DeferredOrder(
                        homeRequest.deferredOrder().idempotencyKey(),
                        DeliveryMethod.store_pickup,
                        TransportMode.customer,
                        null,
                        homeRequest.deferredOrder().notificationContact(),
                        new CreateSaleRequest.StorePickupContact("Cliente", "5555-5555")));
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        stubSalePersistence();
        Order order = stubDeferredOrderPersistence();
        order.setDeliveryMethod(DeliveryMethod.store_pickup);
        PickingOrder picking = picking(order);
        when(pickingService.ensureForOrder(tenant, order.getId())).thenReturn(Optional.of(picking));

        var first = service.create(request);
        ArgumentCaptor<Sale> saleCaptor = ArgumentCaptor.forClass(Sale.class);
        verify(sales).saveAndFlush(saleCaptor.capture());
        Sale savedSale = saleCaptor.getValue();
        ArgumentCaptor<SaleItem> itemCaptor = ArgumentCaptor.forClass(SaleItem.class);
        verify(items).saveAndFlush(itemCaptor.capture());
        SaleItem savedItem = itemCaptor.getValue();
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        when(sales.findByTenantIdAndConfirmationId(tenant, confirmationId))
                .thenReturn(Optional.of(savedSale));
        when(items.findByTenantIdAndSaleId(tenant, savedSale.getId()))
                .thenReturn(List.of(savedItem));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, savedSale.getId()))
                .thenReturn(List.of(savedPayment));
        when(inventoryMovements.findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, List.of("POS_SALE", "POS_KIT_SALE"), savedSale.getId()))
                .thenReturn(List.of());
        when(orders.findByTenantIdAndId(tenant, order.getId())).thenReturn(Optional.of(order));
        when(orderItems.findByOrderId(order.getId())).thenReturn(first.order().items().stream()
                .map(ignored -> savedOrderItem(order.getId(), product, BigDecimal.ONE, BigDecimal.ONE))
                .toList());
        when(pickingOrders.findByTenantIdAndSourceTypeAndSourceId(
                tenant, PickingSourceType.order, order.getId())).thenReturn(Optional.of(picking));

        var replay = service.create(request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.order().id()).isEqualTo(first.order().id());
        assertThat(replay.order().deliveryMethod()).isEqualTo(DeliveryMethod.store_pickup);
        assertThat(replay.pickingOrder().id()).isEqualTo(first.pickingOrder().id());
        assertThat(replay.idempotent()).isTrue();
        verify(orders, times(1)).saveAndFlush(any());
        verify(reservationLifecycle, times(1)).reserve(any());
        verify(pickingService, times(1)).ensureForOrder(any(), any());
        verify(payments, times(1)).save(any());
        verify(cashMovements, times(1)).save(any());
    }

    @Test
    void rejectsDeferredKitsBeforePersistentEffects() {
        Product kit = product();
        kit.setProductType(ProductType.kit);
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(kit));
        assertThatThrownBy(() -> service.create(deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("KIT_FULFILLMENT_NOT_SUPPORTED"));
        verifyNoInteractions(orders, orderItems, reservationLifecycle, pickingService);
        verify(sales, never()).save(any());
        verify(sales, never()).saveAndFlush(any());
    }

    @Test
    void doesNotCreateSalePaymentOrCashMovementWhenDeferredFulfillmentFails() {
        Product product = product();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        Order order = stubDeferredOrderPersistence();
        doThrow(BusinessException.conflict("INSUFFICIENT_STOCK", "Sin stock"))
                .when(reservationLifecycle).reserve(any());

        assertThatThrownBy(() -> service.create(deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK"));
        verify(sales, never()).save(any());
        verify(sales, never()).saveAndFlush(any());
        verifyNoInteractions(payments, cashMovements, pickingService);

        reset(reservationLifecycle);
        when(pickingService.ensureForOrder(tenant, order.getId()))
                .thenThrow(BusinessException.conflict("PICKING_FAILED", "Picking fallo"));
        assertThatThrownBy(() -> service.create(deferredRequest(
                new BigDecimal("20.00"), BigDecimal.ONE, UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PICKING_FAILED"));
        verify(sales, never()).save(any());
        verify(sales, never()).saveAndFlush(any());
        verifyNoInteractions(payments, cashMovements);
    }

    @Test
    void voidDeferredSaleReleasesReservationAndDoesNotRestorePhysicalStock() {
        Sale sale = sale(SaleStatus.completed);
        UUID orderId = UUID.randomUUID();
        sale.setSourceOrderId(orderId);
        Order order = deferredOrder(orderId);
        order.setDeliveryMethod(DeliveryMethod.store_pickup);
        order.setStatus(OrderStatus.ready_for_pickup);
        InventoryReservation reservation = InventoryReservation.builder()
                .status(InventoryReservationStatus.active).build();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        UUID key = UUID.randomUUID();
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.empty());
        when(orders.findByTenantIdAndIdForUpdate(tenant, orderId)).thenReturn(Optional.of(order));
        when(reservations.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(List.of(reservation));
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.empty());
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reversalOperations.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VoidSaleResponse first = service.voidSale(
                sale.getId(), key, new VoidSaleRequest("Pedido cancelado"));

        ArgumentCaptor<SaleReversalOperation> operationCaptor =
                ArgumentCaptor.forClass(SaleReversalOperation.class);
        verify(reversalOperations).saveAndFlush(operationCaptor.capture());
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(operationCaptor.getValue()));
        when(jsonMapper.readValue("{}", VoidSaleResponse.class)).thenReturn(first);

        VoidSaleResponse replay = service.voidSale(
                sale.getId(), key, new VoidSaleRequest("Pedido cancelado"));

        assertThat(first.inventory().inventoryRestored()).isFalse();
        assertThat(first.inventory().reservationsReleased()).isOne();
        assertThat(replay.idempotent()).isTrue();
        verify(reservationLifecycle, times(1)).release(tenant, reservation.getId());
        verify(orders).save(argThat(value -> value.getStatus() == OrderStatus.cancelled));
        verifyNoInteractions(inventory, traceabilityMutation);
        verifyNoInteractions(inventoryMovements);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.cancelled);
    }

    @Test
    void rejectsDeferredVoidAfterReservationWasConsumed() {
        Sale sale = sale(SaleStatus.completed);
        UUID orderId = UUID.randomUUID();
        sale.setSourceOrderId(orderId);
        Order order = deferredOrder(orderId);
        InventoryReservation reservation = InventoryReservation.builder()
                .status(InventoryReservationStatus.consumed).build();
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(orders.findByTenantIdAndIdForUpdate(tenant, orderId)).thenReturn(Optional.of(order));
        when(reservations.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(List.of(reservation));

        assertThatThrownBy(() -> service.voidSale(sale.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("DEFERRED_SALE_ALREADY_FULFILLED"));
        verify(reservationLifecycle, never()).release(any(), any());
        verify(orders, never()).save(any());
        verifyNoInteractions(inventory);
    }

    @Test
    void rejectsStorePickupVoidAfterHandover() {
        Sale sale = sale(SaleStatus.completed);
        UUID orderId = UUID.randomUUID();
        sale.setSourceOrderId(orderId);
        Order order = deferredOrder(orderId);
        order.setDeliveryMethod(DeliveryMethod.store_pickup);
        order.setStatus(OrderStatus.delivered);
        order.setDeliveredAt(Instant.now());
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(orders.findByTenantIdAndIdForUpdate(tenant, orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.voidSale(sale.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("DEFERRED_SALE_ALREADY_FULFILLED"));

        verifyNoInteractions(reservationLifecycle, inventory, inventoryMovements);
        verify(orders, never()).save(any());
    }

    @Test
    void idempotentVoidNormalizesReasonAndReportsOnlyRealCashEffect() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        Payment cash = Payment.builder()
                .method(PaymentMethod.cash)
                .amount(new BigDecimal("8.00"))
                .build();
        Payment card = Payment.builder()
                .method(PaymentMethod.card)
                .amount(new BigDecimal("12.00"))
                .build();
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.empty());
        when(items.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(saleItem()));
        when(products.findByTenantIdAndId(tenant, productId))
                .thenReturn(Optional.of(product()));
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.of(shift()));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(cash, card));
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reversalOperations.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VoidSaleResponse response = service.voidSale(
                sale.getId(), key, new VoidSaleRequest("  Error   de digitacion  "));

        assertThat(response.reason()).isEqualTo("Error de digitacion");
        assertThat(response.idempotent()).isFalse();
        assertThat(response.inventory().inventoryRestored()).isTrue();
        assertThat(response.inventory().reservationsReleased()).isZero();
        assertThat(response.cashMovement().recorded()).isTrue();
        assertThat(response.cashMovement().amount()).isEqualByComparingTo("8.00");
        verify(cashMovements, times(1)).save(any());
        verify(reversalOperations).saveAndFlush(argThat(operation ->
                operation.getReason().equals("Error de digitacion")
                        && operation.getOperationType() == SaleReversalOperationType.void_sale
                        && operation.getExecutedByUserId().equals(user)
                        && operation.getResultPayload().equals("{}")));
    }

    @Test
    void idempotentVoidReplaysStoredResultWithoutMutations() {
        Sale sale = sale(SaleStatus.cancelled);
        UUID key = UUID.randomUUID();
        String reason = "Error de digitacion";
        String fingerprint = ReflectionTestUtils.invokeMethod(
                service, "voidFingerprint", sale.getId(), reason);
        UUID operationId = UUID.randomUUID();
        VoidSaleResponse stored = new VoidSaleResponse(
                operationId,
                false,
                reason,
                com.omniretail.backend.pos.dto.SaleResponse.from(sale),
                new VoidSaleResponse.InventoryEffect(true, List.of(UUID.randomUUID()), 0),
                new VoidSaleResponse.CashMovementEffect(
                        false, List.of(), null));
        SaleReversalOperation operation = operation(
                sale.getId(), key, fingerprint, reason, "snapshot");
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(operation));
        when(jsonMapper.readValue("snapshot", VoidSaleResponse.class)).thenReturn(stored);

        VoidSaleResponse replay = service.voidSale(
                sale.getId(), key, new VoidSaleRequest("  Error de digitacion  "));

        assertThat(replay.operationId()).isEqualTo(operationId);
        assertThat(replay.idempotent()).isTrue();
        verifyNoInteractions(inventory, traceabilityMutation, cashMovements, reservationLifecycle);
        verify(sales, never()).save(any());
    }

    @Test
    void idempotentVoidRejectsChangedPayloadAndKeyReusedForAnotherSale() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        UUID anotherSaleId = UUID.randomUUID();
        SaleReversalOperation changedPayload = operation(
                sale.getId(),
                key,
                ReflectionTestUtils.invokeMethod(
                        service, "voidFingerprint", sale.getId(), "Original"),
                "Original",
                "{}");
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(changedPayload));

        assertThatThrownBy(() -> service.voidSale(
                        sale.getId(), key, new VoidSaleRequest("Changed")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        SaleReversalOperation anotherSale = operation(
                anotherSaleId,
                key,
                "unrelated",
                "Original",
                "{}");
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(anotherSale));

        assertThatThrownBy(() -> service.voidSale(
                        sale.getId(), key, new VoidSaleRequest("Original")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verifyNoInteractions(inventory, traceabilityMutation, cashMovements, reservationLifecycle);
    }

    @Test
    void idempotentVoidRejectsKeyPreviouslyUsedByAnotherOperationType() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        SaleReversalOperation returnOperation = operation(
                sale.getId(), key, "unrelated", "Return", "{}");
        ReflectionTestUtils.setField(
                returnOperation, "operationType", SaleReversalOperationType.return_sale);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(returnOperation));

        assertThatThrownBy(() -> service.voidSale(
                        sale.getId(), key, new VoidSaleRequest("Return")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verifyNoInteractions(inventory, traceabilityMutation, cashMovements, reservationLifecycle);
    }

    @Test
    void idempotentVoidRequiresPosCapabilityBeforeReadingOrMutatingSale() {
        Sale sale = sale(SaleStatus.completed);
        reset(branchAccess);
        doThrow(BusinessException.forbidden(
                        "CAPABILITY_REQUIRED", "La capacidad POS es requerida."))
                .when(capability)
                .ensureTenantCapability(tenant, SaasCapability.pos);

        assertThatThrownBy(() -> service.voidSale(
                        sale.getId(), UUID.randomUUID(), new VoidSaleRequest("Error de digitacion")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("CAPABILITY_REQUIRED"));

        verifyNoInteractions(sales, reversalOperations, inventory, cashMovements);
    }

    @Test
    void idempotentVoidWithClosedOriginalShiftReportsNoCashMovement() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        CashShift closed = shift();
        closed.setStatus(CashShiftStatus.closed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.empty());
        when(items.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(saleItem()));
        when(products.findByTenantIdAndId(tenant, productId))
                .thenReturn(Optional.of(product()));
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.of(closed));
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reversalOperations.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VoidSaleResponse response = service.voidSale(
                sale.getId(), key, new VoidSaleRequest("Turno ya cerrado"));

        assertThat(response.cashMovement().recorded()).isFalse();
        assertThat(response.cashMovement().movementIds()).isEmpty();
        assertThat(response.cashMovement().amount()).isNull();
        verifyNoInteractions(cashMovements);
    }

    @Test
    void voidSaleRevertsStockAndRegistersCashOutWhenShiftIsOpen() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(items.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(saleItem()));
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(Payment.builder().method(PaymentMethod.cash).amount(new BigDecimal("20.00")).build()));
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.of(shift()));
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.voidSale(sale.getId());

        verify(inventory).incrementStock(any());
        verify(cashMovements).save(argThat(movement -> movement.getType().name().equals("out")));
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.cancelled);
    }

    @Test
    void voidSaleRestoresOriginalPhysicalQuantityForConvertedUnit() {
        Sale sale = sale(SaleStatus.completed);
        SaleItem item = saleItem();
        ReflectionTestUtils.setField(item, "quantity", new BigDecimal("2.000"));
        InventoryMovement originalMovement = InventoryMovement.builder()
                .tenantId(tenant).branchId(branch).productId(productId)
                .type(InventoryMovementType.out).reason("Venta")
                .quantity(new BigDecimal("24.000")).referenceType("POS_SALE")
                .referenceId(sale.getId()).build();
        ReflectionTestUtils.setField(originalMovement, "id", UUID.randomUUID());
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(items.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(item));
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(inventoryMovements.findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, Set.of("POS_SALE", "POS_KIT_SALE"), sale.getId()))
                .thenReturn(List.of(originalMovement));
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.empty());
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.voidSale(sale.getId());

        verify(inventory).incrementStock(argThat(command ->
                command.qty().compareTo(new BigDecimal("24.000")) == 0
                        && command.qty().compareTo(new BigDecimal("2.000")) != 0));
    }

    @Test
    void voidSaleRevertsStockWithoutCashMovementWhenShiftIsClosed() {
        Sale sale = sale(SaleStatus.completed);
        CashShift closed = shift();
        closed.setStatus(CashShiftStatus.closed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(items.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(saleItem()));
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(shifts.findByTenantIdAndId(tenant, shiftId)).thenReturn(Optional.of(closed));
        when(sales.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.voidSale(sale.getId());

        verify(inventory).incrementStock(any());
        verifyNoInteractions(cashMovements);
    }

    @Test
    void voidSaleRejectsAlreadyCancelledSale() {
        Sale sale = sale(SaleStatus.cancelled);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.voidSale(sale.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("SALE_ALREADY_VOIDED");
                });
    }

    @Test
    void voidSaleEnforcesBranchAccess() {
        Sale sale = sale(SaleStatus.completed);
        when(branchAccess.resolve(actor)).thenReturn(new BranchAccessResolver.BranchAccess(false, Set.of()));
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.voidSale(sale.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("SALE_NOT_FOUND"));
    }

    @Test
    void listSalesAppliesFiltersAndPagination() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndBranchIdAndStatus(tenant, branch, SaleStatus.completed, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(sale), PageRequest.of(0, 10), 1));

        var page = service.list(branch, SaleStatus.completed, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getSaleDetailReturnsItemsAndPayments() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndId(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(items.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(saleItem()));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(Payment.builder().method(PaymentMethod.cash).amount(new BigDecimal("20.00")).build()));

        var detail = service.get(sale.getId());

        assertThat(detail.items()).hasSize(1);
        assertThat(detail.payments()).hasSize(1);
    }

    private CreateSaleRequest request(BigDecimal payment, BigDecimal quantity) {
        return request(payment, quantity, BigDecimal.ZERO);
    }

    private CreateSaleRequest request(BigDecimal payment, BigDecimal quantity, BigDecimal manualDiscount) {
        return new CreateSaleRequest(branch, shiftId, null, BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, quantity, manualDiscount)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, payment, null)), UUID.randomUUID());
    }

    private CreateSaleRequest deferredRequest(
            BigDecimal payment, BigDecimal quantity, UUID confirmationId) {
        return new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, quantity, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, payment, null)),
                confirmationId,
                null,
                null,
                new CreateSaleRequest.DeferredOrder(
                        "deferred-" + confirmationId,
                        DeliveryMethod.home_delivery,
                        TransportMode.own_fleet,
                        new CreateSaleRequest.DeliveryAddress(
                                "Cliente", "5555-5555", "Zona 1", null,
                                "Guatemala", "Guatemala", null, "Guatemala", "Porton negro"),
                        new CreateSaleRequest.NotificationContact(
                                "send", "cliente@example.com")));
    }

    private Order stubDeferredOrderPersistence() {
        Order order = deferredOrder(UUID.randomUUID());
        when(orders.saveAndFlush(any())).thenAnswer(invocation -> {
            Order value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", order.getId());
            return value;
        });
        return order;
    }

    private Order deferredOrder(UUID id) {
        Order order = Order.builder()
                .branchId(branch)
                .orderNumber("POS-001")
                .source(OrderSource.pos)
                .status(OrderStatus.confirmed)
                .deliveryMethod(DeliveryMethod.home_delivery)
                .transportMode(TransportMode.own_fleet)
                .subtotal(new BigDecimal("20.00"))
                .discountTotal(BigDecimal.ZERO)
                .shippingTotal(BigDecimal.ZERO)
                .total(new BigDecimal("20.00"))
                .trackingToken("pos-test")
                .build();
        order.setTenantId(tenant);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    private PickingOrder picking(Order order) {
        PickingOrder picking = PickingOrder.builder()
                .branchId(branch)
                .sourceType(PickingSourceType.order)
                .sourceId(order.getId())
                .orderId(order.getId())
                .build();
        picking.setTenantId(tenant);
        ReflectionTestUtils.setField(picking, "id", UUID.randomUUID());
        return picking;
    }

    private OrderItem savedOrderItem(
            UUID orderId, Product product, BigDecimal quantity, BigDecimal inventoryQuantity) {
        OrderItem item = OrderItem.builder()
                .orderId(orderId)
                .productId(product.getId())
                .skuSnapshot(product.getSku())
                .nameSnapshot(product.getName())
                .quantity(quantity)
                .inventoryQuantity(inventoryQuantity)
                .unitPrice(product.getSalePrice())
                .discount(BigDecimal.ZERO)
                .subtotal(product.getSalePrice().multiply(quantity))
                .build();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private void stubSalePersistence() {
        when(sales.saveAndFlush(any())).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
            return sale;
        });
    }

    private CreateSaleRequest request(PaymentMethod paymentMethod, String reference) {
        return new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(paymentMethod, new BigDecimal("20.00"), reference)),
                UUID.randomUUID());
    }

    private CashShift shift() {
        CashShift shift = CashShift.builder().branchId(branch).userId(user).status(CashShiftStatus.open).build();
        ReflectionTestUtils.setField(shift, "id", shiftId);
        return shift;
    }

    private Product product() {
        Product product = Product.builder().sku("SKU-1").name("Producto").salePrice(new BigDecimal("20.00"))
                .channelPos(true).build();
        ReflectionTestUtils.setField(product, "id", productId);
        return product;
    }

    private Sale sale(SaleStatus status) {
        Sale sale = Sale.builder().branchId(branch).cashShiftId(shiftId).number("POS-001")
                .subtotal(new BigDecimal("20.00")).discountTotal(BigDecimal.ZERO).taxTotal(BigDecimal.ZERO)
                .total(new BigDecimal("20.00")).createdByUserId(user).status(status).build();
        sale.setTenantId(tenant);
        ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
        return sale;
    }

    private SaleItem saleItem() {
        SaleItem item = SaleItem.builder().productId(productId).skuSnapshot("SKU-1").nameSnapshot("Producto")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("20.00")).discount(BigDecimal.ZERO)
                .subtotal(new BigDecimal("20.00")).build();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private SaleReversalOperation operation(
            UUID saleId,
            UUID key,
            String fingerprint,
            String reason,
            String resultPayload) {
        SaleReversalOperation operation = SaleReversalOperation.builder()
                .tenantId(tenant)
                .branchId(branch)
                .saleId(saleId)
                .operationType(SaleReversalOperationType.void_sale)
                .idempotencyKey(key)
                .fingerprint(fingerprint)
                .reason(reason)
                .executedByUserId(user)
                .resultPayload(resultPayload)
                .build();
        ReflectionTestUtils.setField(operation, "id", UUID.randomUUID());
        return operation;
    }
}
