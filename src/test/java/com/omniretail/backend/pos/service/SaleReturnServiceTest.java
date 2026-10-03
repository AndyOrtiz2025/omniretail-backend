package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityHistoryService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.entity.*;
import com.omniretail.backend.pos.repository.*;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SaleReturnServiceTest {
    @Mock CurrentUser currentUser;
    @Mock TenantCapabilityGuard capability;
    @Mock BranchAccessResolver branches;
    @Mock SaleRepository sales;
    @Mock SaleItemRepository saleItems;
    @Mock SaleReturnRepository returns;
    @Mock SaleReturnItemRepository returnItems;
    @Mock ProductRepository products;
    @Mock InventoryStockService inventory;
    @Mock InventoryTraceabilityMutationService traceabilityMutation;
    @Mock InventoryTraceabilityHistoryService traceabilityHistory;
    @Mock InventoryMovementRepository inventoryMovements;
    @Mock CashShiftRepository shifts;
    @Mock CashMovementRepository movements;
    @Mock PaymentRepository payments;
    @InjectMocks SaleReturnService service;

    UUID tenant = UUID.randomUUID(); UUID user = UUID.randomUUID(); UUID branch = UUID.randomUUID();
    UUID shiftId = UUID.randomUUID(); UUID productId = UUID.randomUUID(); UUID itemId = UUID.randomUUID();
    AuthenticatedUser actor;

    @BeforeEach
    void setUp() {
        actor = new AuthenticatedUser(user, tenant, UserType.employee, UUID.randomUUID(), branch, UUID.randomUUID());
        when(currentUser.require()).thenReturn(actor);
        when(branches.resolve(actor)).thenReturn(new BranchAccessResolver.BranchAccess(false, Set.of(branch)));
        lenient().when(returns.save(any())).thenAnswer(call -> {
            SaleReturn value = call.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
            return value;
        });
        lenient().when(returnItems.saveAndFlush(any())).thenAnswer(call -> {
            SaleReturnItem value = call.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
            return value;
        });
        lenient().when(returnItems.sumReturned(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(returnItems.findByTenantIdAndSaleItemIdIn(any(), any())).thenReturn(List.of());
        lenient().when(traceabilityHistory.expand(any(), any())).thenReturn(Map.of());
        lenient().when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product()));
        lenient().when(sales.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsPartialReturnRevertsStockAndCash() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(item(new BigDecimal("2.000"))));
        when(shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(tenant, branch, user, CashShiftStatus.open))
                .thenReturn(Optional.of(shift(CashShiftStatus.open)));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(Payment.builder().method(PaymentMethod.cash).amount(new BigDecimal("20.00")).build()));

        var result = service.create(sale.getId(), request(BigDecimal.ONE));

        assertThat(result.refundAmount()).isEqualByComparingTo("10.00");
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.partially_returned);
        verify(inventory).incrementStock(argThat(command -> command.qty().compareTo(BigDecimal.ONE) == 0));
        verify(movements).save(argThat(m -> m.getType() == CashMovementType.out && m.getAmount().compareTo(new BigDecimal("10.00")) == 0));
    }

    @Test
    void convertedPartialReturnRestoresItsPhysicalProportion() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(item(new BigDecimal("2.000"))));
        when(inventoryMovements.findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, Set.of("POS_SALE", "POS_KIT_SALE"), sale.getId()))
                .thenReturn(List.of(originalOut(sale, new BigDecimal("24.000"))));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of());

        service.create(sale.getId(), request(BigDecimal.ONE));

        verify(inventory).incrementStock(argThat(command ->
                command.qty().compareTo(new BigDecimal("12.000")) == 0));
    }

    @Test
    void convertedRemainingReturnReconcilesExactlyToOriginalPhysicalOut() {
        Sale sale = sale(SaleStatus.partially_returned);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(item(new BigDecimal("2.000"))));
        when(returnItems.sumReturned(tenant, itemId)).thenReturn(BigDecimal.ONE);
        when(inventoryMovements.findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, Set.of("POS_SALE", "POS_KIT_SALE"), sale.getId()))
                .thenReturn(List.of(originalOut(sale, new BigDecimal("24.000"))));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of());

        service.create(sale.getId(), request(BigDecimal.ONE));

        verify(inventory).incrementStock(argThat(command ->
                command.qty().compareTo(new BigDecimal("12.000")) == 0));
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.returned);
    }

    @Test
    void convertedFullReturnRestoresCompletePhysicalOut() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(item(new BigDecimal("2.000"))));
        when(inventoryMovements.findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                tenant, Set.of("POS_SALE", "POS_KIT_SALE"), sale.getId()))
                .thenReturn(List.of(originalOut(sale, new BigDecimal("24.000"))));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of());

        service.create(sale.getId(), request(new BigDecimal("2.000")));

        verify(inventory).incrementStock(argThat(command ->
                command.qty().compareTo(new BigDecimal("24.000")) == 0));
    }

    @Test
    void rejectsCashRefundWhenNoOpenShiftForUser() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(item(BigDecimal.ONE)));
        when(shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(tenant, branch, user, CashShiftStatus.open))
                .thenReturn(Optional.empty());
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(Payment.builder().method(PaymentMethod.cash)
                        .amount(new BigDecimal("20.00")).build()));

        assertThatThrownBy(() -> service.create(sale.getId(), request(BigDecimal.ONE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_OPEN_CASH_SHIFT"));

        verifyNoInteractions(movements);
    }

    @Test
    void rejectsQuantityGreaterThanRemaining() {
        Sale sale = sale(SaleStatus.completed);
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(item(BigDecimal.ONE)));
        when(returnItems.sumReturned(tenant, itemId)).thenReturn(new BigDecimal("0.500"));

        assertThatThrownBy(() -> service.create(sale.getId(), request(new BigDecimal("0.600"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("RETURN_QUANTITY_EXCEEDED"));
    }

    @Test
    void partialKitReturnRestoresSnapshotComponents() {
        UUID componentId = UUID.randomUUID();
        Sale sale = sale(SaleStatus.completed);
        SaleItem kitItem = item(new BigDecimal("2.000"));
        ReflectionTestUtils.setField(kitItem, "fulfillmentComponents",
                "[{\"productId\":\"" + componentId + "\",\"quantityPerKit\":3.000}]");
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId())).thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId())).thenReturn(List.of(kitItem));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId())).thenReturn(List.of());
        Product component = product();
        ReflectionTestUtils.setField(component, "id", componentId);
        when(products.findByTenantIdAndId(tenant, componentId)).thenReturn(Optional.of(component));

        service.create(sale.getId(), request(BigDecimal.ONE));

        verify(inventory).incrementStock(argThat(command -> command.productId().equals(componentId)
                && command.qty().compareTo(new BigDecimal("3.000")) == 0));
        verify(products, never()).findByTenantIdAndId(tenant, productId);
    }

    private CreateSaleReturnRequest request(BigDecimal quantity) {
        return new CreateSaleReturnRequest("Cliente devolvió el producto", List.of(new CreateSaleReturnRequest.Line(itemId, quantity)));
    }
    private Sale sale(SaleStatus status) {
        Sale sale = Sale.builder().branchId(branch).cashShiftId(shiftId).number("POS-1").status(status)
                .subtotal(new BigDecimal("20.00")).discountTotal(BigDecimal.ZERO).taxTotal(BigDecimal.ZERO)
                .total(new BigDecimal("20.00")).createdByUserId(user).build();
        sale.setTenantId(tenant); ReflectionTestUtils.setField(sale, "id", UUID.randomUUID()); return sale;
    }
    private SaleItem item(BigDecimal quantity) { SaleItem i = SaleItem.builder().saleId(UUID.randomUUID()).productId(productId).skuSnapshot("SKU").nameSnapshot("Producto").quantity(quantity).unitPrice(new BigDecimal("10.00")).discount(BigDecimal.ZERO).subtotal(new BigDecimal("20.00")).build(); ReflectionTestUtils.setField(i,"id",itemId); return i; }
    private Product product() { Product p=Product.builder().sku("SKU").name("Producto").salePrice(new BigDecimal("10.00")).channelPos(true).build(); ReflectionTestUtils.setField(p,"id",productId); return p; }
    private InventoryMovement originalOut(Sale sale, BigDecimal quantity) {
        return InventoryMovement.builder().tenantId(tenant).branchId(sale.getBranchId())
                .productId(productId).type(InventoryMovementType.out).reason("Venta")
                .quantity(quantity).referenceType("POS_SALE").referenceId(sale.getId()).build();
    }
    private CashShift shift(CashShiftStatus status) { CashShift s=CashShift.builder().branchId(branch).userId(user).registerCode("A").status(status).openingAmount(BigDecimal.ZERO).build(); ReflectionTestUtils.setField(s,"id",shiftId); return s; }
}
