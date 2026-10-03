package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.service.KitFulfillmentSnapshot;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.InventoryHistoricalTraceDetail;
import com.omniretail.backend.inventory.dto.InventoryRestoreCommand;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityHistoryService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.InventoryTrackingDetailResponse;
import com.omniretail.backend.pos.dto.InventoryTrackingSelectionRequest;
import com.omniretail.backend.pos.dto.SaleReturnResponse;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleReturn;
import com.omniretail.backend.pos.entity.SaleReturnItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.pos.repository.SaleReturnItemRepository;
import com.omniretail.backend.pos.repository.SaleReturnRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SaleReturnService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard capability;
    private final BranchAccessResolver branches;
    private final SaleRepository sales;
    private final SaleItemRepository saleItems;
    private final SaleReturnRepository returns;
    private final SaleReturnItemRepository returnItems;
    private final ProductRepository products;
    private final InventoryStockService inventory;
    private final InventoryTraceabilityMutationService traceabilityMutation;
    private final InventoryTraceabilityHistoryService traceabilityHistory;
    private final InventoryMovementRepository inventoryMovements;
    private final CashShiftRepository shifts;
    private final CashMovementRepository movements;
    private final PaymentRepository payments;

    public SaleReturnResponse create(UUID saleId, CreateSaleReturnRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);

        Sale sale = sales.findByTenantIdAndIdForUpdate(actor.tenantId(), saleId)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branches.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        if (sale.getStatus() != SaleStatus.completed
                && sale.getStatus() != SaleStatus.partially_returned) {
            throw new BusinessException(HttpStatus.CONFLICT, "SALE_NOT_RETURNABLE",
                    "La venta no admite devoluciones.");
        }

        Map<UUID, SaleItem> available = saleItems.findByTenantIdAndSaleId(actor.tenantId(), saleId)
                .stream().collect(Collectors.toMap(SaleItem::getId, item -> item));
        List<InventoryMovement> originalMovements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        actor.tenantId(), Set.of("POS_SALE", "POS_KIT_SALE"), saleId);
        Map<UUID, List<InventoryHistoricalTraceDetail>> originalHistory =
                traceabilityHistory.expand(actor.tenantId(), originalMovements);
        List<SaleReturnItem> previousReturnItems = available.isEmpty()
                ? List.of()
                : returnItems.findByTenantIdAndSaleItemIdIn(actor.tenantId(), available.keySet());
        List<InventoryMovement> previousReturnMovements = previousReturnItems.isEmpty()
                ? List.of()
                : inventoryMovements
                        .findByTenantIdAndReferenceTypeInAndReferenceLineIdInOrderByCreatedAtAscIdAsc(
                                actor.tenantId(),
                                Set.of("POS_SALE_RETURN", "POS_KIT_SALE_RETURN"),
                                previousReturnItems.stream().map(SaleReturnItem::getId).toList());
        Map<UUID, List<InventoryHistoricalTraceDetail>> previousReturnHistory =
                traceabilityHistory.expand(actor.tenantId(), previousReturnMovements);
        Map<UUID, SaleReturnItem> previousReturnItemsById = previousReturnItems.stream()
                .collect(Collectors.toMap(SaleReturnItem::getId, item -> item));
        Set<UUID> seen = new HashSet<>();
        List<SaleReturnItem> created = new ArrayList<>();
        List<ReturnInventoryPlan> inventoryPlans = new ArrayList<>();
        Map<UUID, BigDecimal> returnedQuantities = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;

        SaleReturn newReturn = SaleReturn.builder()
                .branchId(sale.getBranchId()).saleId(saleId).reason(request.reason())
                .createdByUserId(actor.userId()).refundAmount(BigDecimal.ZERO).build();
        newReturn.setTenantId(actor.tenantId());
        SaleReturn saleReturn = returns.save(newReturn);

        for (CreateSaleReturnRequest.Line line : request.lines()) {
            if (!seen.add(line.saleItemId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_RETURN_ITEM",
                        "La línea está repetida.");
            }
            SaleItem item = available.get(line.saleItemId());
            if (item == null) {
                throw notFound("SALE_ITEM_NOT_FOUND", "Línea no encontrada.");
            }
            BigDecimal previous = Optional.ofNullable(returnItems.sumReturned(actor.tenantId(), item.getId()))
                    .orElse(BigDecimal.ZERO);
            if (line.quantity().compareTo(item.getQuantity().subtract(previous)) > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "RETURN_QUANTITY_EXCEEDED",
                        "La cantidad supera la disponible para devolución.");
            }
            BigDecimal unitNet = item.getUnitPrice().subtract(
                    item.getDiscount().divide(item.getQuantity(), 8, RoundingMode.HALF_UP));
            BigDecimal refund = unitNet.multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP);
            total = total.add(refund);
            returnedQuantities.put(item.getId(), previous.add(line.quantity()));

            SaleReturnItem returnItem = SaleReturnItem.builder()
                    .returnId(saleReturn.getId()).saleItemId(item.getId()).productId(item.getProductId())
                    .quantity(line.quantity()).refundAmount(refund).build();
            returnItem.setTenantId(actor.tenantId());
            SaleReturnItem savedReturnItem = returnItems.saveAndFlush(returnItem);
            created.add(savedReturnItem);
            inventoryPlans.addAll(returnInventoryPlans(
                    actor.tenantId(),
                    item,
                    savedReturnItem,
                    line.quantity(),
                    line.trackingSelections(),
                    originalMovements,
                    originalHistory,
                    previousReturnMovements,
                    previousReturnHistory,
                    previousReturnItemsById));
        }

        inventoryPlans.stream()
                .sorted(Comparator.comparing((ReturnInventoryPlan plan) -> plan.product().getId())
                        .thenComparing(ReturnInventoryPlan::locationId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(plan -> plan.returnItem().getId()))
                .forEach(plan -> restoreReturnedInventory(actor, sale, saleReturn, plan));

        saleReturn.setRefundAmount(total.setScale(2, RoundingMode.HALF_UP));
        returns.save(saleReturn);
        registerCashRefund(actor, sale, saleReturn, total);

        boolean allReturned = available.values().stream().allMatch(item -> item.getQuantity().compareTo(
                returnedQuantities.containsKey(item.getId()) ? returnedQuantities.get(item.getId())
                        : Optional.ofNullable(returnItems.sumReturned(actor.tenantId(), item.getId()))
                                .orElse(BigDecimal.ZERO)) <= 0);
        sale.setStatus(allReturned ? SaleStatus.returned : SaleStatus.partially_returned);
        sales.save(sale);
        List<InventoryMovement> createdMovements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        actor.tenantId(),
                        Set.of("POS_SALE_RETURN", "POS_KIT_SALE_RETURN"),
                        saleReturn.getId());
        return SaleReturnResponse.from(
                saleReturn,
                created,
                trackingByReturnItem(
                        createdMovements,
                        traceabilityHistory.expand(actor.tenantId(), createdMovements)));
    }

    private List<ReturnInventoryPlan> returnInventoryPlans(
            UUID tenantId,
            SaleItem saleItem,
            SaleReturnItem returnItem,
            BigDecimal returnQuantity,
            List<InventoryTrackingSelectionRequest> requestedSelections,
            List<InventoryMovement> originalMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> originalHistory,
            List<InventoryMovement> previousReturnMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> previousReturnHistory,
            Map<UUID, SaleReturnItem> previousReturnItemsById) {
        List<InventoryTrackingSelectionRequest> selections = requestedSelections == null
                ? List.of()
                : requestedSelections;
        Map<UUID, ExpectedReturnInventory> expected = expectedReturnInventory(
                tenantId, saleItem, returnQuantity);
        for (InventoryTrackingSelectionRequest selection : selections) {
            if (selection == null || !expected.containsKey(selection.productId())) {
                throw invalidTrackingSelection(
                        "La seleccion no corresponde a inventario de la linea vendida.");
            }
        }

        List<ReturnInventoryPlan> plans = new ArrayList<>();
        for (ExpectedReturnInventory value : expected.values()) {
            List<InventoryTrackingSelectionRequest> productSelections = selections.stream()
                    .filter(selection -> value.product().getId().equals(selection.productId()))
                    .toList();
            boolean historicalTraceable = !historicalDetails(
                            saleItem.getId(),
                            value.product().getId(),
                            originalMovements,
                            originalHistory)
                    .isEmpty();
            if (historicalTraceable != isTraceable(value.product())) {
                throw inconsistentSaleHistory();
            }
            if (!isTraceable(value.product())) {
                if (!productSelections.isEmpty()) {
                    throw invalidTrackingSelection(
                            "El producto no utiliza trazabilidad por lote o serie.");
                }
                plans.add(new ReturnInventoryPlan(
                        returnItem,
                        value.product(),
                        null,
                        value.quantity(),
                        List.of(),
                        value.kitComponent(),
                        false));
                continue;
            }
            validateTrackedReturnSelection(
                    saleItem,
                    value,
                    productSelections,
                    originalMovements,
                    originalHistory,
                    previousReturnMovements,
                    previousReturnHistory,
                    previousReturnItemsById);
            Map<UUID, List<InventoryTrackingSelectionRequest>> byLocation = productSelections.stream()
                    .collect(Collectors.groupingBy(InventoryTrackingSelectionRequest::locationId));
            byLocation.forEach((locationId, locationSelections) -> plans.add(new ReturnInventoryPlan(
                    returnItem,
                    value.product(),
                    locationId,
                    locationSelections.stream()
                            .map(InventoryTrackingSelectionRequest::quantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    locationSelections.stream()
                            .map(selection -> new InventoryTraceabilitySelection(
                                    selection.lotId(),
                                    selection.quantity(),
                                    safeSerials(selection.serialNumbers())))
                            .toList(),
                    value.kitComponent(),
                    true)));
        }
        return plans;
    }

    private Map<UUID, ExpectedReturnInventory> expectedReturnInventory(
            UUID tenantId, SaleItem saleItem, BigDecimal returnQuantity) {
        List<KitFulfillmentSnapshot.Component> fulfillment =
                KitFulfillmentSnapshot.decode(saleItem.getFulfillmentComponents());
        Map<UUID, ExpectedReturnInventory> expected = new HashMap<>();
        if (fulfillment.isEmpty()) {
            Product product = products.findByTenantIdAndId(tenantId, saleItem.getProductId())
                    .orElseThrow(SaleReturnService::inconsistentSaleHistory);
            if (product.getProductType() == ProductType.physical
                    && Boolean.TRUE.equals(product.getTrackingStock())) {
                expected.put(product.getId(), new ExpectedReturnInventory(
                        product, returnQuantity, false));
            }
            return expected;
        }
        for (KitFulfillmentSnapshot.Component component : fulfillment) {
            Product product = products.findByTenantIdAndId(tenantId, component.productId())
                    .orElseThrow(SaleReturnService::inconsistentSaleHistory);
            expected.put(product.getId(), new ExpectedReturnInventory(
                    product,
                    returnQuantity.multiply(component.quantityPerKit()),
                    true));
        }
        return expected;
    }

    private void validateTrackedReturnSelection(
            SaleItem saleItem,
            ExpectedReturnInventory expected,
            List<InventoryTrackingSelectionRequest> selections,
            List<InventoryMovement> originalMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> originalHistory,
            List<InventoryMovement> previousReturnMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> previousReturnHistory,
            Map<UUID, SaleReturnItem> previousReturnItemsById) {
        if (selections.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_SELECTIONS_REQUIRED",
                    "La devolucion trazable requiere seleccionar el inventario vendido.");
        }
        if (selections.stream().anyMatch(selection -> selection.locationId() == null)) {
            throw invalidTrackingSelection("La ubicacion original es requerida.");
        }
        BigDecimal selectedQuantity = selections.stream()
                .map(InventoryTrackingSelectionRequest::quantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (selectedQuantity.compareTo(expected.quantity()) != 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La seleccion devuelta no coincide con la cantidad fisica requerida.");
        }

        List<InventoryHistoricalTraceDetail> original = historicalDetails(
                saleItem.getId(), expected.product().getId(), originalMovements, originalHistory);
        List<InventoryHistoricalTraceDetail> returned = previousReturnMovements.stream()
                .filter(movement -> {
                    SaleReturnItem previous = previousReturnItemsById.get(movement.getReferenceLineId());
                    return previous != null
                            && previous.getSaleItemId().equals(saleItem.getId())
                            && movement.getProductId().equals(expected.product().getId());
                })
                .flatMap(movement -> previousReturnHistory
                        .getOrDefault(movement.getId(), List.of()).stream())
                .toList();
        if (original.isEmpty()) throw inconsistentSaleHistory();

        if (Boolean.TRUE.equals(expected.product().getTrackingSerial())) {
            validateSerialReturnSelections(expected.product(), selections, original, returned);
        } else {
            validateLotReturnSelections(selections, original, returned);
        }
    }

    private static void validateSerialReturnSelections(
            Product product,
            List<InventoryTrackingSelectionRequest> selections,
            List<InventoryHistoricalTraceDetail> original,
            List<InventoryHistoricalTraceDetail> returned) {
        Map<String, TraceIdentity> sold = serialIdentities(original);
        Set<String> alreadyReturned = serialIdentities(returned).keySet();
        Set<String> requested = new HashSet<>();
        for (InventoryTrackingSelectionRequest selection : selections) {
            List<String> numbers = safeSerials(selection.serialNumbers()).stream()
                    .map(number -> number == null ? null : number.trim())
                    .toList();
            if (numbers.isEmpty()
                    || numbers.stream().anyMatch(Objects::isNull)
                    || selection.quantity() == null
                    || selection.quantity().stripTrailingZeros().scale() > 0
                    || selection.quantity().compareTo(BigDecimal.valueOf(numbers.size())) != 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_COUNT_MISMATCH",
                        "La cantidad devuelta debe coincidir con los seriales seleccionados.");
            }
            for (String number : numbers) {
                if (number.isEmpty() || !requested.add(number)) {
                    throw BusinessException.conflict(
                            "DUPLICATE_SERIAL", "Un serial no puede devolverse dos veces.");
                }
                TraceIdentity identity = sold.get(number);
                if (identity == null || alreadyReturned.contains(number)) {
                    throw BusinessException.conflict(
                            "SERIAL_NOT_RETURNABLE",
                            "El serial no pertenece a la venta o ya fue devuelto.");
                }
                if (!identity.locationId().equals(selection.locationId())
                        || !Objects.equals(identity.lotId(), selection.lotId())) {
                    throw invalidTrackingSelection(
                            "El serial no coincide con su ubicacion y lote originales.");
                }
                if (Boolean.TRUE.equals(product.getTrackingLot()) && selection.lotId() == null) {
                    throw invalidTrackingSelection("El lote original del serial es requerido.");
                }
                if (!Boolean.TRUE.equals(product.getTrackingLot()) && selection.lotId() != null) {
                    throw invalidTrackingSelection("El producto no utiliza lote.");
                }
            }
        }
    }

    private static void validateLotReturnSelections(
            List<InventoryTrackingSelectionRequest> selections,
            List<InventoryHistoricalTraceDetail> original,
            List<InventoryHistoricalTraceDetail> returned) {
        Map<TraceKey, BigDecimal> sold = quantitiesByTrace(original);
        Map<TraceKey, BigDecimal> alreadyReturned = quantitiesByTrace(returned);
        Map<TraceKey, BigDecimal> requested = new HashMap<>();
        for (InventoryTrackingSelectionRequest selection : selections) {
            if (selection.lotId() == null || !safeSerials(selection.serialNumbers()).isEmpty()) {
                throw invalidTrackingSelection(
                        "La devolucion por lote requiere lote y no admite seriales.");
            }
            TraceKey key = new TraceKey(selection.locationId(), selection.lotId());
            requested.merge(key, selection.quantity(), BigDecimal::add);
        }
        requested.forEach((key, quantity) -> {
            BigDecimal remaining = sold.getOrDefault(key, BigDecimal.ZERO)
                    .subtract(alreadyReturned.getOrDefault(key, BigDecimal.ZERO));
            if (quantity.compareTo(remaining) > 0) {
                throw BusinessException.conflict(
                        "RETURN_TRACE_QUANTITY_EXCEEDED",
                        "La cantidad del lote supera lo vendido pendiente de devolucion.");
            }
        });
    }

    private static List<InventoryHistoricalTraceDetail> historicalDetails(
            UUID saleItemId,
            UUID productId,
            List<InventoryMovement> movements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> history) {
        return movements.stream()
                .filter(movement -> saleItemId.equals(movement.getReferenceLineId())
                        && productId.equals(movement.getProductId()))
                .flatMap(movement -> history.getOrDefault(movement.getId(), List.of()).stream())
                .toList();
    }

    private static Map<String, TraceIdentity> serialIdentities(
            List<InventoryHistoricalTraceDetail> details) {
        Map<String, TraceIdentity> result = new HashMap<>();
        for (InventoryHistoricalTraceDetail detail : details) {
            detail.serialNumbers().forEach(number -> result.put(
                    number, new TraceIdentity(detail.locationId(), detail.lotId())));
        }
        return result;
    }

    private static Map<TraceKey, BigDecimal> quantitiesByTrace(
            List<InventoryHistoricalTraceDetail> details) {
        Map<TraceKey, BigDecimal> result = new HashMap<>();
        details.forEach(detail -> result.merge(
                new TraceKey(detail.locationId(), detail.lotId()),
                detail.quantity(),
                BigDecimal::add));
        return result;
    }

    private void restoreReturnedInventory(
            AuthenticatedUser actor,
            Sale sale,
            SaleReturn saleReturn,
            ReturnInventoryPlan plan) {
        String referenceType = plan.kitComponent()
                ? "POS_KIT_SALE_RETURN"
                : "POS_SALE_RETURN";
        String reason = (plan.kitComponent() ? "Devolucion venta kit POS #" : "Devolución venta POS #")
                + sale.getNumber();
        if (!plan.traceable()) {
            inventory.incrementStock(new AddStockCommand(
                    actor.tenantId(),
                    sale.getBranchId(),
                    plan.product().getId(),
                    plan.quantity(),
                    reason,
                    referenceType,
                    saleReturn.getId(),
                    plan.returnItem().getId(),
                    actor.userId()));
            return;
        }
        traceabilityMutation.restore(new InventoryRestoreCommand(
                actor.tenantId(),
                sale.getBranchId(),
                plan.product(),
                plan.locationId(),
                plan.quantity(),
                plan.selections(),
                reason,
                referenceType,
                saleReturn.getId(),
                plan.returnItem().getId(),
                actor.userId()));
    }

    private static Map<UUID, List<InventoryTrackingDetailResponse>> trackingByReturnItem(
            List<InventoryMovement> movements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> history) {
        Map<UUID, List<InventoryTrackingDetailResponse>> result = new HashMap<>();
        for (InventoryMovement movement : movements) {
            if (movement.getReferenceLineId() == null) continue;
            List<InventoryTrackingDetailResponse> values = result.computeIfAbsent(
                    movement.getReferenceLineId(), ignored -> new ArrayList<>());
            history.getOrDefault(movement.getId(), List.of()).stream()
                    .map(detail -> new InventoryTrackingDetailResponse(
                            detail.productId(),
                            detail.locationId(),
                            detail.lotId(),
                            detail.lotNumber(),
                            detail.quantity(),
                            detail.serialNumbers()))
                    .forEach(values::add);
        }
        return result;
    }

    private void registerCashRefund(AuthenticatedUser actor, Sale sale, SaleReturn saleReturn,
            BigDecimal total) {
        BigDecimal cash = payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), sale.getId())
                .stream().filter(payment -> payment.getMethod() == PaymentMethod.cash)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashRefund = sale.getTotal().signum() == 0 ? BigDecimal.ZERO
                : total.multiply(cash).divide(sale.getTotal(), 2, RoundingMode.HALF_UP);
        if (cash.signum() <= 0 || cashRefund.signum() <= 0) {
            return;
        }
        Optional<CashShift> currentShift = shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(
                actor.tenantId(), sale.getBranchId(), actor.userId(), CashShiftStatus.open);
        if (currentShift.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_OPEN_CASH_SHIFT",
                    "Se requiere un turno de caja abierto para registrar el egreso de efectivo.");
        }
        movements.save(CashMovement.builder().tenantId(actor.tenantId())
                .cashShiftId(currentShift.get().getId()).type(CashMovementType.out).amount(cashRefund)
                .reason("Devolución venta POS #" + sale.getNumber()).referenceType("sale_return")
                .referenceId(saleReturn.getId()).createdByUserId(actor.userId()).build());
    }

    @Transactional(readOnly = true)
    public Page<SaleReturnResponse> list(UUID branchId, Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        if (!branches.resolve(actor).allows(branchId)) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        Page<SaleReturn> page = returns.findByTenantIdAndBranchId(
                actor.tenantId(), branchId, pageable);
        List<UUID> returnIds = page.getContent().stream().map(SaleReturn::getId).toList();
        List<SaleReturnItem> pageItems = returnIds.isEmpty()
                ? List.of()
                : returnItems.findByTenantIdAndReturnIdIn(actor.tenantId(), returnIds);
        Map<UUID, List<SaleReturnItem>> itemsByReturn = pageItems.stream()
                .collect(Collectors.groupingBy(SaleReturnItem::getReturnId));
        List<InventoryMovement> pageMovements = returnIds.isEmpty()
                ? List.of()
                : inventoryMovements
                        .findByTenantIdAndReferenceTypeInAndReferenceIdInOrderByCreatedAtAscIdAsc(
                                actor.tenantId(),
                                Set.of("POS_SALE_RETURN", "POS_KIT_SALE_RETURN"),
                                returnIds);
        Map<UUID, List<InventoryTrackingDetailResponse>> tracking = trackingByReturnItem(
                pageMovements,
                traceabilityHistory.expand(actor.tenantId(), pageMovements));
        return page.map(value -> SaleReturnResponse.from(
                value,
                itemsByReturn.getOrDefault(value.getId(), List.of()),
                tracking));
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private static boolean isTraceable(Product product) {
        return product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock())
                && (Boolean.TRUE.equals(product.getTrackingLot())
                        || Boolean.TRUE.equals(product.getTrackingSerial()));
    }

    private static List<String> safeSerials(List<String> serialNumbers) {
        return serialNumbers == null ? List.of() : serialNumbers;
    }

    private static BusinessException invalidTrackingSelection(String message) {
        return new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_TRACKING_SELECTION", message);
    }

    private static BusinessException inconsistentSaleHistory() {
        return BusinessException.conflict(
                "SALE_INVENTORY_HISTORY_INCONSISTENT",
                "El historial trazable de la venta es inconsistente.");
    }

    private record ExpectedReturnInventory(
            Product product, BigDecimal quantity, boolean kitComponent) {}

    private record ReturnInventoryPlan(
            SaleReturnItem returnItem,
            Product product,
            UUID locationId,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections,
            boolean kitComponent,
            boolean traceable) {}

    private record TraceKey(UUID locationId, UUID lotId) {}

    private record TraceIdentity(UUID locationId, UUID lotId) {}
}
