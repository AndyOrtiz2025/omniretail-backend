package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityHistoryService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.InventoryTrackingSelectionRequest;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SaleReturnServiceTest {
    @Mock CurrentUser currentUser;
    @Mock TenantCapabilityGuard capability;
    @Mock BranchAccessResolver branches;
    @Mock SaleRepository sales;
    @Mock SaleItemRepository saleItems;
    @Mock SaleReturnRepository returns;
    @Mock SaleReturnItemRepository returnItems;
    @Mock SaleReversalOperationRepository reversalOperations;
    @Mock ProductRepository products;
    @Mock CustomerRepository customers;
    @Mock OrderRepository orders;
    @Mock InventoryReservationRepository reservations;
    @Mock InventoryStockService inventory;
    @Mock InventoryTraceabilityMutationService traceabilityMutation;
    @Mock InventoryTraceabilityHistoryService traceabilityHistory;
    @Mock InventoryMovementRepository inventoryMovements;
    @Mock CashShiftRepository shifts;
    @Mock CashMovementRepository movements;
    @Mock PaymentRepository payments;
    @Mock JsonMapper jsonMapper;
    @InjectMocks SaleReturnService service;

    UUID tenant = UUID.randomUUID(); UUID user = UUID.randomUUID(); UUID branch = UUID.randomUUID();
    UUID shiftId = UUID.randomUUID(); UUID productId = UUID.randomUUID(); UUID itemId = UUID.randomUUID();
    AuthenticatedUser actor;

    @BeforeEach
    void setUp() {
        actor = new AuthenticatedUser(user, tenant, UserType.employee, UUID.randomUUID(), branch, UUID.randomUUID());
        lenient().when(currentUser.require()).thenReturn(actor);
        lenient().when(branches.resolve(actor))
                .thenReturn(new BranchAccessResolver.BranchAccess(false, Set.of(branch)));
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
        lenient().when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
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
    void rejectsDeferredSaleReturnBeforeAnyPersistentEffect() {
        Sale sale = sale(SaleStatus.completed);
        sale.setSourceOrderId(UUID.randomUUID());
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.create(sale.getId(), request(BigDecimal.ONE)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("DEFERRED_SALE_RETURN_NOT_SUPPORTED"));

        verifyNoInteractions(saleItems, inventory, traceabilityMutation, inventoryMovements,
                returns, returnItems, shifts, movements, payments);
        verify(sales, never()).save(any());
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

    @Test
    void idempotentReturnPersistsAndReplaysOriginalEffectsOnlyOnce() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        InventoryMovement restored = InventoryMovement.builder()
                .tenantId(tenant)
                .branchId(branch)
                .productId(productId)
                .type(InventoryMovementType.in)
                .quantity(BigDecimal.ONE)
                .reason("Devolucion")
                .build();
        ReflectionTestUtils.setField(restored, "id", UUID.randomUUID());
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.empty());
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(item(BigDecimal.ONE)));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of());
        when(inventory.incrementStock(any())).thenReturn(restored);
        when(reversalOperations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var first = service.create(
                sale.getId(), key, new CreateSaleReturnRequest(
                        "  Producto   danado  ",
                        List.of(new CreateSaleReturnRequest.Line(itemId, BigDecimal.ONE))));

        ArgumentCaptor<SaleReversalOperation> operation =
                ArgumentCaptor.forClass(SaleReversalOperation.class);
        verify(reversalOperations).saveAndFlush(operation.capture());
        assertThat(operation.getValue().getReason()).isEqualTo("Producto danado");
        assertThat(operation.getValue().getOperationType())
                .isEqualTo(SaleReversalOperationType.return_sale);
        assertThat(first.idempotent()).isFalse();
        assertThat(first.inventory().movementIds()).containsExactly(restored.getId());

        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(operation.getValue()));
        when(jsonMapper.readValue("{}", com.omniretail.backend.pos.dto.SaleReturnOperationResponse.class))
                .thenReturn(first);

        var replay = service.create(
                sale.getId(), key, new CreateSaleReturnRequest(
                        "Producto danado",
                        List.of(new CreateSaleReturnRequest.Line(itemId, BigDecimal.ONE))));

        assertThat(replay.idempotent()).isTrue();
        verify(inventory, times(1)).incrementStock(any());
        verify(returns, times(2)).save(any());
        verify(returnItems, times(1)).saveAndFlush(any());
    }

    @Test
    void idempotentReturnRejectsKeyUsedByVoid() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        SaleReversalOperation operation = SaleReversalOperation.builder()
                .id(UUID.randomUUID())
                .tenantId(tenant)
                .branchId(branch)
                .saleId(sale.getId())
                .operationType(SaleReversalOperationType.void_sale)
                .idempotencyKey(key)
                .fingerprint("other")
                .reason("Void")
                .executedByUserId(user)
                .resultPayload("{}")
                .build();
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(operation));

        assertThatThrownBy(() -> service.create(sale.getId(), key, request(BigDecimal.ONE)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verifyNoInteractions(inventory, traceabilityMutation, movements);
    }

    @Test
    void idempotentReturnRejectsSameKeyWithDifferentContent() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        SaleReversalOperation operation = SaleReversalOperation.builder()
                .id(UUID.randomUUID())
                .tenantId(tenant)
                .branchId(branch)
                .saleId(sale.getId())
                .operationType(SaleReversalOperationType.return_sale)
                .idempotencyKey(key)
                .fingerprint("different-fingerprint")
                .reason("Otro motivo")
                .executedByUserId(user)
                .resultPayload("{}")
                .build();
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.of(operation));

        assertThatThrownBy(() -> service.create(sale.getId(), key, request(BigDecimal.ONE)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verifyNoInteractions(inventory, traceabilityMutation, movements);
    }

    @Test
    void returnFingerprintDoesNotDependOnLineTraceOrSerialOrder() {
        UUID secondItemId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        InventoryTrackingSelectionRequest firstTrace = new InventoryTrackingSelectionRequest(
                productId,
                locationId,
                lotId,
                new BigDecimal("2.000"),
                List.of("SERIAL-2", "SERIAL-1"));
        InventoryTrackingSelectionRequest reorderedTrace = new InventoryTrackingSelectionRequest(
                productId,
                locationId,
                lotId,
                new BigDecimal("2.0"),
                List.of("SERIAL-1", "SERIAL-2"));
        CreateSaleReturnRequest first = new CreateSaleReturnRequest(
                "Producto danado",
                List.of(
                        new CreateSaleReturnRequest.Line(
                                itemId, BigDecimal.ONE, List.of(firstTrace)),
                        new CreateSaleReturnRequest.Line(
                                secondItemId, new BigDecimal("2.000"), List.of())));
        CreateSaleReturnRequest reordered = new CreateSaleReturnRequest(
                "Producto danado",
                List.of(
                        new CreateSaleReturnRequest.Line(
                                secondItemId, new BigDecimal("2.0"), List.of()),
                        new CreateSaleReturnRequest.Line(
                                itemId, new BigDecimal("1.000"), List.of(reorderedTrace))));

        UUID saleId = UUID.randomUUID();
        String firstFingerprint = ReflectionTestUtils.invokeMethod(
                SaleReturnService.class, "returnFingerprint", saleId, first);
        String reorderedFingerprint = ReflectionTestUtils.invokeMethod(
                SaleReturnService.class, "returnFingerprint", saleId, reordered);

        assertThat(reorderedFingerprint).isEqualTo(firstFingerprint);
    }

    @Test
    void idempotentMixedPaymentReturnReportsOnlyActualCashOutflow() {
        Sale sale = sale(SaleStatus.completed);
        UUID key = UUID.randomUUID();
        CashMovement cashMovement = CashMovement.builder()
                .tenantId(tenant)
                .cashShiftId(shiftId)
                .type(CashMovementType.out)
                .amount(new BigDecimal("4.00"))
                .reason("Devolucion")
                .createdByUserId(user)
                .build();
        ReflectionTestUtils.setField(cashMovement, "id", UUID.randomUUID());
        when(sales.findByTenantIdAndIdForUpdate(tenant, sale.getId()))
                .thenReturn(Optional.of(sale));
        when(reversalOperations.findByTenantIdAndIdempotencyKey(tenant, key))
                .thenReturn(Optional.empty());
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(item(BigDecimal.ONE)));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(
                        Payment.builder().method(PaymentMethod.cash).amount(new BigDecimal("8.00")).build(),
                        Payment.builder().method(PaymentMethod.card).amount(new BigDecimal("12.00")).build()));
        when(shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(
                        tenant, branch, user, CashShiftStatus.open))
                .thenReturn(Optional.of(shift(CashShiftStatus.open)));
        when(movements.save(any())).thenReturn(cashMovement);
        when(reversalOperations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.create(sale.getId(), key, request(BigDecimal.ONE));

        assertThat(result.commercialRefundAmount()).isEqualByComparingTo("10.00");
        assertThat(result.cashMovement().recorded()).isTrue();
        assertThat(result.cashMovement().amount()).isEqualByComparingTo("4.00");
        assertThat(result.cashMovement().movementIds()).containsExactly(cashMovement.getId());
        verify(movements).save(argThat(movement ->
                movement.getAmount().compareTo(new BigDecimal("4.00")) == 0));
    }

    @Test
    void eligibilityReportsSoldReturnedAndReturnableWithoutMutations() {
        Sale sale = sale(SaleStatus.partially_returned);
        SaleReturnItem previous = SaleReturnItem.builder()
                .returnId(UUID.randomUUID())
                .saleItemId(itemId)
                .productId(productId)
                .quantity(new BigDecimal("0.500"))
                .refundAmount(new BigDecimal("5.00"))
                .build();
        previous.setTenantId(tenant);
        ReflectionTestUtils.setField(previous, "id", UUID.randomUUID());
        when(sales.findByTenantIdAndBranchIdAndNumber(tenant, branch, "POS-1"))
                .thenReturn(Optional.of(sale));
        when(saleItems.findByTenantIdAndSaleId(tenant, sale.getId()))
                .thenReturn(List.of(item(new BigDecimal("2.000"))));
        when(returnItems.findByTenantIdAndSaleItemIdIn(eq(tenant), any()))
                .thenReturn(List.of(previous));
        when(payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(tenant, sale.getId()))
                .thenReturn(List.of(Payment.builder()
                        .method(PaymentMethod.card)
                        .status(PaymentStatus.approved)
                        .amount(new BigDecimal("20.00"))
                        .currency("GTQ")
                        .build()));

        var result = service.eligibility(branch, " POS-1 ");

        assertThat(result.items()).singleElement().satisfies(line -> {
            assertThat(line.soldQuantity()).isEqualByComparingTo("2.000");
            assertThat(line.returnedQuantity()).isEqualByComparingTo("0.500");
            assertThat(line.returnableQuantity()).isEqualByComparingTo("1.500");
            assertThat(line.canReturn()).isTrue();
        });
        assertThat(result.allowedOperations().partialReturn()).isTrue();
        assertThat(result.actorHasOpenCashShift()).isFalse();
        verifyNoInteractions(inventory, traceabilityMutation);
        verify(sales, never()).save(any());
        verify(returns, never()).save(any());
    }

    @Test
    void eligibilityHidesSalesWhenBranchIsNotAuthorized() {
        UUID unauthorizedBranch = UUID.randomUUID();
        when(branches.resolve(actor))
                .thenReturn(new BranchAccessResolver.BranchAccess(false, Set.of(branch)));

        assertThatThrownBy(() -> service.eligibility(unauthorizedBranch, "POS-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                            assertThat(exception.getCode()).isEqualTo("SALE_NOT_FOUND");
                        });

        verifyNoInteractions(sales);
    }

    @Test
    void eligibilityReturnsNotFoundForUnknownDocumentWithinAuthorizedBranch() {
        when(sales.findByTenantIdAndBranchIdAndNumber(tenant, branch, "MISSING"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.eligibility(branch, " MISSING "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                            assertThat(exception.getCode()).isEqualTo("SALE_NOT_FOUND");
                        });
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
