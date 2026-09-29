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
import com.omniretail.backend.catalog.repository.ProductRepository;
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
    @Mock BusinessCapabilitiesConfigRepository businessConfig;
    @Mock BankAccountRepository bankAccounts;
    @Mock SaleRepository sales;
    @Mock SaleItemRepository items;
    @Mock PaymentRepository payments;
    @Mock CashMovementRepository cashMovements;
    @Mock InventoryStockService inventory;
    @Mock DocumentCounterService counter;
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
    void rejectsPaymentsThatDoNotMatchTotal() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        assertThatThrownBy(() -> service.create(request(new BigDecimal("19.99"), BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_TOTAL_MISMATCH"));
        verifyNoInteractions(sales, inventory, items, payments);
    }

    @Test
    void returnsExistingSaleForRepeatedConfirmation() {
        UUID confirmationId = UUID.randomUUID();
        Sale existing = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndConfirmationId(tenant, confirmationId)).thenReturn(Optional.of(existing));

        var result = service.create(new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, new BigDecimal("20.00"), null)),
                confirmationId));

        assertThat(result.id()).isEqualTo(existing.getId());
        verifyNoInteractions(inventory, items, payments, cashMovements);
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
        assertThatThrownBy(() -> service.create(request(new BigDecimal("20.00"), BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
        verifyNoInteractions(sales, inventory, items, payments);
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
        return new CreateSaleRequest(branch, shiftId, null, BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, quantity, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, payment, null)));
    }

    private CreateSaleRequest request(PaymentMethod paymentMethod, String reference) {
        return new CreateSaleRequest(
                branch,
                shiftId,
                null,
                BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, BigDecimal.ONE, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(paymentMethod, new BigDecimal("20.00"), reference)));
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
