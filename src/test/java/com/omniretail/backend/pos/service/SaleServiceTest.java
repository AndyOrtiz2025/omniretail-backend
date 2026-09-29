package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.PaymentMethod;
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

@ExtendWith(MockitoExtension.class)
class SaleServiceTest {
    @Mock CurrentUser currentUser;
    @Mock TenantCapabilityGuard capability;
    @Mock BranchAccessResolver branchAccess;
    @Mock CashShiftRepository shifts;
    @Mock ProductRepository products;
    @Mock SaleRepository sales;
    @Mock SaleItemRepository items;
    @Mock PaymentRepository payments;
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
        when(shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(tenant, branch, user, CashShiftStatus.open))
                .thenReturn(Optional.of(shift()));
        lenient().when(counter.nextPosSaleNumber(tenant)).thenReturn("POS-001");
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
    void rejectsProductUnavailableForPos() {
        when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request(new BigDecimal("20.00"), BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
        verifyNoInteractions(sales, inventory, items, payments);
    }

    @Test
    void rejectsShiftNotOwnedByCashier() {
        when(shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(tenant, branch, user, CashShiftStatus.open))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request(new BigDecimal("20.00"), BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CASH_SHIFT_NOT_FOUND"));
        verifyNoInteractions(products, sales, inventory, items, payments);
    }

    private CreateSaleRequest request(BigDecimal payment, BigDecimal quantity) {
        return new CreateSaleRequest(branch, shiftId, null, BigDecimal.ZERO,
                List.of(new CreateSaleRequest.Item(productId, quantity, BigDecimal.ZERO)),
                List.of(new CreateSaleRequest.PaymentLine(PaymentMethod.cash, payment, null)));
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
}
