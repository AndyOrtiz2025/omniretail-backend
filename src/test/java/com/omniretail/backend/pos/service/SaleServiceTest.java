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
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import com.omniretail.backend.administration.entity.UserType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
    @Mock SaleItemRepository items;
    @Mock PaymentRepository payments;
    @Mock CashMovementRepository cashMovements;
    @Mock InventoryStockService inventory;
    @Mock DocumentCounterService counter;
    @Mock ProductPriceResolver productPriceResolver;
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
                        eq(tenant), any(Product.class), any(Instant.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"),
                        new BigDecimal("20.00"),
                        BigDecimal.ZERO.setScale(2),
                        null));
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
        verify(items).save(any());
        verify(payments).save(any());
        verify(cashMovements).save(any());
    }

    @Test
    void promotionWinsAgainstZeroManualDiscountAndIsSnapshotted() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("15.00"), BigDecimal.ONE, BigDecimal.ZERO));

        ArgumentCaptor<SaleItem> saved = ArgumentCaptor.forClass(SaleItem.class);
        verify(items).save(saved.capture());
        assertThat(saved.getValue().getDiscount()).isEqualByComparingTo("5.00");
        assertThat(saved.getValue().getSubtotal()).isEqualByComparingTo("15.00");
        assertThat(saved.getValue().getPromotionId()).isEqualTo(promotionId);
        assertThat(saved.getValue().getUnitPrice()).isEqualByComparingTo("20.00");
    }

    @Test
    void greaterManualDiscountWinsAndClearsPromotionSnapshot() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("13.00"), BigDecimal.ONE, new BigDecimal("7.00")));

        ArgumentCaptor<SaleItem> saved = ArgumentCaptor.forClass(SaleItem.class);
        verify(items).save(saved.capture());
        assertThat(saved.getValue().getDiscount()).isEqualByComparingTo("7.00");
        assertThat(saved.getValue().getPromotionId()).isNull();
    }

    @Test
    void equalDiscountDeterministicallyPrefersPromotion() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("15.00"), BigDecimal.ONE, new BigDecimal("5.00")));

        verify(items).save(argThat(item -> promotionId.equals(item.getPromotionId())));
    }

    @Test
    void comparesPromotionAndManualDiscountAtLineQuantity() {
        UUID promotionId = UUID.randomUUID();
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), any(Product.class), any(Instant.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), new BigDecimal("15.00"),
                        new BigDecimal("5.00"), promotionId));
        stubSalePersistence();

        service.create(request(new BigDecimal("30.00"), new BigDecimal("2"), new BigDecimal("7.00")));

        verify(items).save(argThat(item ->
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
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), eq(discountedProduct), any(Instant.class)))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("20.00"), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("20.00"), promotionId));
        when(productPriceResolver.resolveEffectivePrice(eq(tenant), eq(additionalProduct), any(Instant.class)))
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
        verify(items).save(argThat(item ->
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

        var result = service.create(request);

        assertThat(result.id()).isEqualTo(existing.getId());
        verifyNoInteractions(inventory, items, payments, cashMovements);
        verifyNoInteractions(productPriceResolver);
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
        return SaleItem.builder().productId(productId).skuSnapshot("SKU-1").nameSnapshot("Producto")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("20.00")).discount(BigDecimal.ZERO)
                .subtotal(new BigDecimal("20.00")).build();
    }
}
