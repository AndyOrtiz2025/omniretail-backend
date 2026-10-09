package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.service.KitFulfillmentSnapshot;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.InventoryHistoricalTraceDetail;
import com.omniretail.backend.inventory.dto.InventoryRestoreCommand;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityHistoryService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.dto.CreateSaleReturnRequest;
import com.omniretail.backend.pos.dto.InventoryTrackingDetailResponse;
import com.omniretail.backend.pos.dto.InventoryTrackingSelectionRequest;
import com.omniretail.backend.pos.dto.SaleReturnEligibilityResponse;
import com.omniretail.backend.pos.dto.SaleReturnOperationResponse;
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
import com.omniretail.backend.pos.entity.SaleReversalOperation;
import com.omniretail.backend.pos.entity.SaleReversalOperationType;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.pos.repository.SaleReturnItemRepository;
import com.omniretail.backend.pos.repository.SaleReturnRepository;
import com.omniretail.backend.pos.repository.SaleReversalOperationRepository;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
    private final SaleReversalOperationRepository reversalOperations;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final InventoryReservationRepository reservations;
    private final InventoryStockService inventory;
    private final InventoryTraceabilityMutationService traceabilityMutation;
    private final InventoryTraceabilityHistoryService traceabilityHistory;
    private final InventoryMovementRepository inventoryMovements;
    private final CashShiftRepository shifts;
    private final CashMovementRepository movements;
    private final PaymentRepository payments;
    private final JsonMapper jsonMapper;

    public SaleReturnResponse create(UUID saleId, CreateSaleReturnRequest request) {
        AuthenticatedUser actor = requireActor();
        Sale sale = requireAuthorizedSaleForUpdate(actor, saleId);
        return executeReturn(actor, sale, request).response();
    }

    public SaleReturnOperationResponse create(
            UUID saleId, UUID idempotencyKey, CreateSaleReturnRequest request) {
        AuthenticatedUser actor = requireActor();
        String reason = normalizeReason(request.reason());
        CreateSaleReturnRequest normalizedRequest =
                new CreateSaleReturnRequest(reason, request.lines());
        String fingerprint = returnFingerprint(saleId, normalizedRequest);

        reversalOperations.acquireIdempotencyLock(actor.tenantId() + ":" + idempotencyKey);
        Sale sale = requireAuthorizedSaleForUpdate(actor, saleId);
        Optional<SaleReversalOperation> previous =
                reversalOperations.findByTenantIdAndIdempotencyKey(
                        actor.tenantId(), idempotencyKey);
        if (previous.isPresent()) {
            return replayReturn(previous.get(), sale, fingerprint);
        }

        ReturnExecutionResult executed = executeReturn(actor, sale, normalizedRequest);
        UUID operationId = UUID.randomUUID();
        SaleReturnOperationResponse response = new SaleReturnOperationResponse(
                operationId,
                false,
                reason,
                sale.getStatus(),
                executed.response(),
                executed.response().refundAmount(),
                new SaleReturnOperationResponse.InventoryEffect(
                        !executed.inventoryMovementIds().isEmpty(),
                        executed.inventoryMovementIds()),
                new SaleReturnOperationResponse.CashMovementEffect(
                        !executed.cashMovementIds().isEmpty(),
                        executed.cashMovementIds(),
                        executed.cashMovementAmount()));
        reversalOperations.saveAndFlush(SaleReversalOperation.builder()
                .id(operationId)
                .tenantId(actor.tenantId())
                .branchId(sale.getBranchId())
                .saleId(sale.getId())
                .operationType(SaleReversalOperationType.return_sale)
                .idempotencyKey(idempotencyKey)
                .fingerprint(fingerprint)
                .reason(reason)
                .executedByUserId(actor.userId())
                .resultPayload(jsonMapper.writeValueAsString(response))
                .build());
        return response;
    }

    private AuthenticatedUser requireActor() {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        return actor;
    }

    private Sale requireAuthorizedSaleForUpdate(AuthenticatedUser actor, UUID saleId) {
        Sale sale = sales.findByTenantIdAndIdForUpdate(actor.tenantId(), saleId)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branches.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        return sale;
    }

    private ReturnExecutionResult executeReturn(
            AuthenticatedUser actor, Sale sale, CreateSaleReturnRequest request) {
        UUID saleId = sale.getId();

        if (sale.getSourceOrderId() != null) {
            throw BusinessException.conflict(
                    "DEFERRED_SALE_RETURN_NOT_SUPPORTED",
                    "Las devoluciones de ventas POS diferidas aun no estan soportadas.");
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
                    sale,
                    item,
                    savedReturnItem,
                    previous,
                    line.quantity(),
                    line.trackingSelections(),
                    originalMovements,
                    originalHistory,
                    previousReturnMovements,
                    previousReturnHistory,
                    previousReturnItemsById));
        }

        List<UUID> inventoryMovementIds = new ArrayList<>();
        inventoryPlans.stream()
                .sorted(Comparator.comparing((ReturnInventoryPlan plan) -> plan.product().getId())
                        .thenComparing(ReturnInventoryPlan::locationId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(plan -> plan.returnItem().getId()))
                .map(plan -> restoreReturnedInventory(actor, sale, saleReturn, plan))
                .filter(Objects::nonNull)
                .map(InventoryMovement::getId)
                .filter(Objects::nonNull)
                .forEach(inventoryMovementIds::add);

        saleReturn.setRefundAmount(total.setScale(2, RoundingMode.HALF_UP));
        returns.save(saleReturn);
        CashMovement cashMovement = registerCashRefund(actor, sale, saleReturn, total);

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
        SaleReturnResponse response = SaleReturnResponse.from(
                saleReturn,
                created,
                trackingByReturnItem(
                        createdMovements,
                        traceabilityHistory.expand(actor.tenantId(), createdMovements)));
        return new ReturnExecutionResult(
                response,
                List.copyOf(inventoryMovementIds),
                cashMovement == null || cashMovement.getId() == null
                        ? List.of()
                        : List.of(cashMovement.getId()),
                cashMovement == null ? null : cashMovement.getAmount());
    }

    private List<ReturnInventoryPlan> returnInventoryPlans(
            UUID tenantId,
            Sale sale,
            SaleItem saleItem,
            SaleReturnItem returnItem,
            BigDecimal previouslyReturnedCommercial,
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
                tenantId,
                sale,
                saleItem,
                previouslyReturnedCommercial,
                returnQuantity,
                originalMovements);
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
            UUID tenantId,
            Sale sale,
            SaleItem saleItem,
            BigDecimal previouslyReturnedCommercial,
            BigDecimal returnQuantity,
            List<InventoryMovement> originalMovements) {
        List<KitFulfillmentSnapshot.Component> fulfillment =
                KitFulfillmentSnapshot.decode(saleItem.getFulfillmentComponents());
        Map<UUID, ExpectedReturnInventory> expected = new HashMap<>();
        if (fulfillment.isEmpty()) {
            Product product = products.findByTenantIdAndId(tenantId, saleItem.getProductId())
                    .orElseThrow(SaleReturnService::inconsistentSaleHistory);
            if (product.getProductType() == ProductType.physical
                    && Boolean.TRUE.equals(product.getTrackingStock())) {
                expected.put(product.getId(), new ExpectedReturnInventory(
                        product,
                        physicalReturnQuantity(
                                sale,
                                saleItem,
                                product.getId(),
                                false,
                                previouslyReturnedCommercial,
                                returnQuantity,
                                originalMovements,
                                saleItem.getQuantity()),
                        false));
            }
            return expected;
        }
        for (KitFulfillmentSnapshot.Component component : fulfillment) {
            Product product = products.findByTenantIdAndId(tenantId, component.productId())
                    .orElseThrow(SaleReturnService::inconsistentSaleHistory);
            expected.put(product.getId(), new ExpectedReturnInventory(
                    product,
                    physicalReturnQuantity(
                            sale,
                            saleItem,
                            product.getId(),
                            true,
                            previouslyReturnedCommercial,
                            returnQuantity,
                            originalMovements,
                            saleItem.getQuantity().multiply(component.quantityPerKit())),
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

    private InventoryMovement restoreReturnedInventory(
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
            // La unidad vuelve al balance del que salio la venta original (venta y linea vendida).
            return inventory.restoreSoldStock(
                    new AddStockCommand(
                            actor.tenantId(),
                            sale.getBranchId(),
                            plan.product().getId(),
                            plan.quantity(),
                            reason,
                            referenceType,
                            saleReturn.getId(),
                            plan.returnItem().getId(),
                            actor.userId()),
                    sale.getId(),
                    plan.returnItem().getSaleItemId());
        }
        return traceabilityMutation.restore(new InventoryRestoreCommand(
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

    private static BigDecimal physicalReturnQuantity(
            Sale sale,
            SaleItem item,
            UUID productId,
            boolean kitComponent,
            BigDecimal previouslyReturnedCommercial,
            BigDecimal returnedCommercial,
            List<InventoryMovement> originalMovements,
            BigDecimal legacyOriginalPhysical) {
        List<InventoryMovement> lineMovements = originalMovements.stream()
                .filter(movement -> movement.getType() == InventoryMovementType.out)
                .filter(movement -> sale.getBranchId().equals(movement.getBranchId()))
                .filter(movement -> productId.equals(movement.getProductId()))
                .filter(movement -> item.getId().equals(movement.getReferenceLineId()))
                .toList();
        if (lineMovements.isEmpty() && !kitComponent) {
            lineMovements = originalMovements.stream()
                    .filter(movement -> movement.getType() == InventoryMovementType.out)
                    .filter(movement -> sale.getBranchId().equals(movement.getBranchId()))
                    .filter(movement -> productId.equals(movement.getProductId()))
                    .filter(movement -> movement.getReferenceLineId() == null)
                    .filter(movement -> "POS_SALE".equals(movement.getReferenceType()))
                    .toList();
        }
        BigDecimal originalPhysicalOut = lineMovements.stream()
                .map(InventoryMovement::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (originalPhysicalOut.signum() == 0) {
            originalPhysicalOut = inventoryQuantity(legacyOriginalPhysical);
        }

        BigDecimal cumulativeCommercial = previouslyReturnedCommercial.add(returnedCommercial);
        BigDecimal previousPhysicalTarget = physicalTarget(
                originalPhysicalOut, previouslyReturnedCommercial, item.getQuantity());
        BigDecimal cumulativePhysicalTarget = cumulativeCommercial.compareTo(item.getQuantity()) == 0
                ? originalPhysicalOut.setScale(3, RoundingMode.UNNECESSARY)
                : physicalTarget(originalPhysicalOut, cumulativeCommercial, item.getQuantity());
        BigDecimal result = cumulativePhysicalTarget.subtract(previousPhysicalTarget);
        if (result.signum() <= 0) {
            throw invalidPhysicalReturn();
        }
        return inventoryQuantity(result);
    }

    private static BigDecimal physicalTarget(
            BigDecimal originalPhysicalOut,
            BigDecimal returnedCommercial,
            BigDecimal originalCommercial) {
        if (returnedCommercial.signum() == 0) {
            return BigDecimal.ZERO.setScale(3);
        }
        try {
            return originalPhysicalOut.multiply(returnedCommercial)
                    .divide(originalCommercial, 3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalidPhysicalReturn();
        }
    }

    private static BigDecimal inventoryQuantity(BigDecimal quantity) {
        try {
            return quantity.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalidPhysicalReturn();
        }
    }

    private static BusinessException invalidPhysicalReturn() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "RETURN_INVENTORY_QUANTITY_INVALID",
                "La cantidad devuelta no puede representarse con la precisión de inventario.");
    }

    private CashMovement registerCashRefund(AuthenticatedUser actor, Sale sale, SaleReturn saleReturn,
            BigDecimal total) {
        BigDecimal cash = payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), sale.getId())
                .stream().filter(payment -> payment.getMethod() == PaymentMethod.cash)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashRefund = sale.getTotal().signum() == 0 ? BigDecimal.ZERO
                : total.multiply(cash).divide(sale.getTotal(), 2, RoundingMode.HALF_UP);
        if (cash.signum() <= 0 || cashRefund.signum() <= 0) {
            return null;
        }
        Optional<CashShift> currentShift = shifts.findByTenantIdAndBranchIdAndUserIdAndStatus(
                actor.tenantId(), sale.getBranchId(), actor.userId(), CashShiftStatus.open);
        if (currentShift.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_OPEN_CASH_SHIFT",
                    "Se requiere un turno de caja abierto para registrar el egreso de efectivo.");
        }
        return movements.save(CashMovement.builder().tenantId(actor.tenantId())
                .cashShiftId(currentShift.get().getId()).type(CashMovementType.out).amount(cashRefund)
                .reason("Devolución venta POS #" + sale.getNumber()).referenceType("sale_return")
                .referenceId(saleReturn.getId()).createdByUserId(actor.userId()).build());
    }

    private SaleReturnOperationResponse replayReturn(
            SaleReversalOperation operation, Sale sale, String fingerprint) {
        if (operation.getOperationType() != SaleReversalOperationType.return_sale
                || !operation.getSaleId().equals(sale.getId())
                || !operation.getBranchId().equals(sale.getBranchId())
                || !operation.getFingerprint().equals(fingerprint)) {
            throw BusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "La clave de idempotencia ya fue utilizada con otra operacion.");
        }
        return jsonMapper
                .readValue(operation.getResultPayload(), SaleReturnOperationResponse.class)
                .asReplay();
    }

    private static String normalizeReason(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank() || normalized.length() > 1000) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "RETURN_REASON_INVALID",
                    "El motivo de devolucion es obligatorio y no puede exceder 1000 caracteres.");
        }
        return normalized;
    }

    private static String returnFingerprint(UUID saleId, CreateSaleReturnRequest request) {
        String lines = request.lines().stream()
                .map(SaleReturnService::returnLineFingerprint)
                .sorted()
                .collect(Collectors.joining(";"));
        return sha256(SaleReversalOperationType.return_sale
                + "|" + saleId
                + "|" + request.reason()
                + "|" + lines);
    }

    private static String returnLineFingerprint(CreateSaleReturnRequest.Line line) {
        if (line == null) return "null";
        String selections = Optional.ofNullable(line.trackingSelections())
                .orElse(List.of())
                .stream()
                .map(SaleReturnService::trackingFingerprint)
                .sorted()
                .collect(Collectors.joining(","));
        return line.saleItemId()
                + ":" + decimalFingerprint(line.quantity())
                + ":[" + selections + "]";
    }

    private static String trackingFingerprint(InventoryTrackingSelectionRequest selection) {
        if (selection == null) return "null";
        String serials = safeSerials(selection.serialNumbers()).stream()
                .map(value -> value == null ? "null" : value.trim())
                .sorted()
                .collect(Collectors.joining(","));
        return selection.productId()
                + ":" + selection.locationId()
                + ":" + selection.lotId()
                + ":" + decimalFingerprint(selection.quantity())
                + ":[" + serials + "]";
    }

    private static String decimalFingerprint(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no esta disponible.", exception);
        }
    }

    @Transactional(readOnly = true)
    public SaleReturnEligibilityResponse eligibility(UUID branchId, String documentNumber) {
        AuthenticatedUser actor = requireActor();
        if (!branches.resolve(actor).allows(branchId)) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        String normalizedNumber = documentNumber == null ? "" : documentNumber.trim();
        if (normalizedNumber.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_NUMBER_REQUIRED",
                    "El numero de documento es requerido.");
        }
        Sale sale = sales.findByTenantIdAndBranchIdAndNumber(
                        actor.tenantId(), branchId, normalizedNumber)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        return eligibility(actor, sale);
    }

    private SaleReturnEligibilityResponse eligibility(AuthenticatedUser actor, Sale sale) {
        List<SaleItem> items = saleItems.findByTenantIdAndSaleId(actor.tenantId(), sale.getId());
        Map<UUID, SaleItem> itemsById = items.stream()
                .collect(Collectors.toMap(SaleItem::getId, item -> item));
        List<SaleReturnItem> priorItems = itemsById.isEmpty()
                ? List.of()
                : returnItems.findByTenantIdAndSaleItemIdIn(actor.tenantId(), itemsById.keySet());
        Map<UUID, BigDecimal> returnedByItem = priorItems.stream().collect(Collectors.groupingBy(
                SaleReturnItem::getSaleItemId,
                Collectors.reducing(BigDecimal.ZERO, SaleReturnItem::getQuantity, BigDecimal::add)));
        Map<UUID, SaleReturnItem> priorItemsById = priorItems.stream()
                .collect(Collectors.toMap(SaleReturnItem::getId, item -> item));

        List<InventoryMovement> originalMovements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        actor.tenantId(), Set.of("POS_SALE", "POS_KIT_SALE"), sale.getId());
        Map<UUID, List<InventoryHistoricalTraceDetail>> originalHistory =
                traceabilityHistory.expand(actor.tenantId(), originalMovements);
        List<InventoryMovement> priorReturnMovements = priorItems.isEmpty()
                ? List.of()
                : inventoryMovements
                        .findByTenantIdAndReferenceTypeInAndReferenceLineIdInOrderByCreatedAtAscIdAsc(
                                actor.tenantId(),
                                Set.of("POS_SALE_RETURN", "POS_KIT_SALE_RETURN"),
                                priorItemsById.keySet());
        Map<UUID, List<InventoryHistoricalTraceDetail>> priorReturnHistory =
                traceabilityHistory.expand(actor.tenantId(), priorReturnMovements);

        List<Payment> salePayments = payments
                .findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), sale.getId());
        boolean needsCashShift = salePayments.stream()
                .anyMatch(payment -> payment.getMethod() == PaymentMethod.cash
                        && payment.getAmount().signum() > 0);
        boolean actorHasOpenShift = shifts
                .findByTenantIdAndBranchIdAndUserIdAndStatus(
                        actor.tenantId(), sale.getBranchId(), actor.userId(), CashShiftStatus.open)
                .isPresent();
        boolean cashShiftRequirementSatisfied = !needsCashShift || actorHasOpenShift;
        boolean baseReturnAllowed = sale.getSourceOrderId() == null
                && (sale.getStatus() == SaleStatus.completed
                        || sale.getStatus() == SaleStatus.partially_returned)
                && cashShiftRequirementSatisfied;

        List<SaleReturnEligibilityResponse.Item> responseItems = new ArrayList<>();
        for (SaleItem item : items) {
            BigDecimal returned = returnedByItem.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal returnable = item.getQuantity().subtract(returned).max(BigDecimal.ZERO);
            TraceEligibility trace = traceEligibility(
                    actor.tenantId(),
                    item,
                    originalMovements,
                    originalHistory,
                    priorReturnMovements,
                    priorReturnHistory,
                    priorItemsById);
            boolean canReturn = baseReturnAllowed && returnable.signum() > 0 && trace.safe();
            String blockedReason = canReturn
                    ? null
                    : itemBlockedReason(
                            sale, cashShiftRequirementSatisfied, returnable, trace.safe());
            responseItems.add(new SaleReturnEligibilityResponse.Item(
                    item.getId(),
                    item.getProductId(),
                    item.getSkuSnapshot(),
                    item.getNameSnapshot(),
                    item.getQuantity(),
                    returned,
                    returnable,
                    item.getUnitPrice(),
                    item.getDiscount(),
                    item.getSubtotal(),
                    canReturn,
                    blockedReason,
                    trace.options()));
        }

        boolean partialReturnAllowed = responseItems.stream()
                .anyMatch(SaleReturnEligibilityResponse.Item::canReturn);
        String returnBlockedReason = partialReturnAllowed
                ? null
                : overallReturnBlockedReason(sale, cashShiftRequirementSatisfied, responseItems);
        boolean voidAllowed = voidAllowed(actor.tenantId(), sale);
        String voidBlockedReason = voidAllowed
                ? null
                : "La venta no cumple las condiciones actuales para anulacion total.";

        List<SaleReturn> priorReturns = returns
                .findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), sale.getId());
        BigDecimal previouslyReturnedAmount = priorReturns.stream()
                .map(SaleReturn::getRefundAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        List<UUID> returnIds = priorReturns.stream().map(SaleReturn::getId).toList();
        BigDecimal cashRefundRecorded = returnIds.isEmpty()
                ? BigDecimal.ZERO.setScale(2)
                : movements
                        .findByTenantIdAndReferenceTypeAndReferenceIdInOrderByCreatedAtAscIdAsc(
                                actor.tenantId(), "sale_return", returnIds)
                        .stream()
                        .map(CashMovement::getAmount)
                        .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        boolean originalShiftOpen = shifts.findByTenantIdAndId(
                        actor.tenantId(), sale.getCashShiftId())
                .filter(shift -> shift.getStatus() == CashShiftStatus.open)
                .isPresent();

        return new SaleReturnEligibilityResponse(
                new SaleReturnEligibilityResponse.Sale(
                        sale.getId(),
                        sale.getNumber(),
                        sale.getCreatedAt(),
                        customerDisplayName(actor.tenantId(), sale),
                        sale.getTotal(),
                        sale.getStatus()),
                List.copyOf(responseItems),
                salePayments.stream()
                        .map(payment -> new SaleReturnEligibilityResponse.Payment(
                                payment.getId(),
                                payment.getMethod(),
                                payment.getStatus(),
                                payment.getAmount(),
                                payment.getCurrency()))
                        .toList(),
                previouslyReturnedAmount,
                cashRefundRecorded,
                originalShiftOpen,
                actorHasOpenShift,
                new SaleReturnEligibilityResponse.AllowedOperations(
                        voidAllowed,
                        partialReturnAllowed,
                        voidBlockedReason,
                        returnBlockedReason));
    }

    private TraceEligibility traceEligibility(
            UUID tenantId,
            SaleItem saleItem,
            List<InventoryMovement> originalMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> originalHistory,
            List<InventoryMovement> priorReturnMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> priorReturnHistory,
            Map<UUID, SaleReturnItem> priorItemsById) {
        List<UUID> productIds = KitFulfillmentSnapshot.decode(saleItem.getFulfillmentComponents())
                .stream()
                .map(KitFulfillmentSnapshot.Component::productId)
                .toList();
        if (productIds.isEmpty()) productIds = List.of(saleItem.getProductId());

        boolean safe = true;
        List<SaleReturnEligibilityResponse.TraceOption> options = new ArrayList<>();
        for (UUID productId : productIds) {
            Product product = products.findByTenantIdAndId(tenantId, productId).orElse(null);
            if (product == null) {
                safe = false;
                continue;
            }
            if (product.getProductType() != ProductType.physical
                    || !Boolean.TRUE.equals(product.getTrackingStock())) {
                continue;
            }
            List<InventoryHistoricalTraceDetail> original = historicalDetails(
                    saleItem.getId(), productId, originalMovements, originalHistory);
            if (!isTraceable(product)) {
                if (!original.isEmpty()) safe = false;
                continue;
            }
            List<InventoryHistoricalTraceDetail> returned = priorReturnMovements.stream()
                    .filter(movement -> {
                        SaleReturnItem previous = priorItemsById.get(movement.getReferenceLineId());
                        return previous != null
                                && previous.getSaleItemId().equals(saleItem.getId())
                                && productId.equals(movement.getProductId());
                    })
                    .flatMap(movement -> priorReturnHistory
                            .getOrDefault(movement.getId(), List.of()).stream())
                    .toList();
            if (original.isEmpty()
                    || original.stream().anyMatch(detail -> detail.locationId() == null)
                    || Boolean.TRUE.equals(product.getTrackingLot())
                            && original.stream().anyMatch(detail -> detail.lotId() == null)) {
                safe = false;
                continue;
            }
            List<SaleReturnEligibilityResponse.TraceOption> productOptions =
                    availableTraceOptions(product, original, returned);
            if (productOptions.isEmpty()) safe = false;
            options.addAll(productOptions);
        }
        return new TraceEligibility(safe, List.copyOf(options));
    }

    private static List<SaleReturnEligibilityResponse.TraceOption> availableTraceOptions(
            Product product,
            List<InventoryHistoricalTraceDetail> original,
            List<InventoryHistoricalTraceDetail> returned) {
        if (Boolean.TRUE.equals(product.getTrackingSerial())) {
            Set<String> returnedSerials = returned.stream()
                    .flatMap(detail -> detail.serialNumbers().stream())
                    .collect(Collectors.toSet());
            Map<TraceOptionKey, List<String>> serials = new HashMap<>();
            original.forEach(detail -> {
                TraceOptionKey key = new TraceOptionKey(
                        detail.locationId(), detail.lotId(), detail.lotNumber());
                detail.serialNumbers().stream()
                        .filter(number -> !returnedSerials.contains(number))
                        .forEach(number -> serials
                                .computeIfAbsent(key, ignored -> new ArrayList<>())
                                .add(number));
            });
            return serials.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new SaleReturnEligibilityResponse.TraceOption(
                            product.getId(),
                            entry.getKey().locationId(),
                            entry.getKey().lotId(),
                            entry.getKey().lotNumber(),
                            BigDecimal.valueOf(entry.getValue().size()).setScale(3),
                            entry.getValue().stream().sorted().toList()))
                    .toList();
        }

        Map<TraceOptionKey, BigDecimal> sold = quantitiesByTraceOption(original);
        Map<TraceOptionKey, BigDecimal> restored = quantitiesByTraceOption(returned);
        return sold.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey(),
                        entry.getValue().subtract(restored.getOrDefault(
                                entry.getKey(), BigDecimal.ZERO))))
                .filter(entry -> entry.getValue().signum() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SaleReturnEligibilityResponse.TraceOption(
                        product.getId(),
                        entry.getKey().locationId(),
                        entry.getKey().lotId(),
                        entry.getKey().lotNumber(),
                        entry.getValue(),
                        List.of()))
                .toList();
    }

    private static Map<TraceOptionKey, BigDecimal> quantitiesByTraceOption(
            List<InventoryHistoricalTraceDetail> details) {
        Map<TraceOptionKey, BigDecimal> result = new HashMap<>();
        details.forEach(detail -> result.merge(
                new TraceOptionKey(detail.locationId(), detail.lotId(), detail.lotNumber()),
                detail.quantity(),
                BigDecimal::add));
        return result;
    }

    private boolean voidAllowed(UUID tenantId, Sale sale) {
        if (sale.getStatus() != SaleStatus.completed) return false;
        if (sale.getSourceOrderId() == null) return true;
        Order order = orders.findByTenantIdAndId(tenantId, sale.getSourceOrderId())
                .filter(found -> found.getBranchId().equals(sale.getBranchId())
                        && found.getSource() == OrderSource.pos
                        && (found.getDeliveryMethod() == DeliveryMethod.home_delivery
                                || found.getDeliveryMethod() == DeliveryMethod.store_pickup))
                .orElse(null);
        if (order == null
                || order.getStatus() == OrderStatus.dispatched
                || order.getStatus() == OrderStatus.delivered) {
            return false;
        }
        return reservations.findByTenantIdAndOrderId(tenantId, order.getId()).stream()
                .noneMatch(reservation ->
                        reservation.getStatus() == InventoryReservationStatus.consumed);
    }

    private String customerDisplayName(UUID tenantId, Sale sale) {
        if (sale.getDocumentLegalName() != null && !sale.getDocumentLegalName().isBlank()) {
            return sale.getDocumentLegalName();
        }
        if (sale.getCustomerId() != null) {
            Optional<Customer> customer = customers.findByTenantIdAndId(tenantId, sale.getCustomerId());
            if (customer.isPresent() && !customer.get().getName().isBlank()) {
                return customer.get().getName();
            }
        }
        if (sale.getSourceOrderId() != null) {
            Optional<Order> order = orders.findByTenantIdAndId(tenantId, sale.getSourceOrderId());
            if (order.isPresent() && order.get().getGuestCustomer() != null) {
                Map<String, Object> guest = jsonMapper.readValue(
                        order.get().getGuestCustomer(),
                        new TypeReference<Map<String, Object>>() {});
                Object name = guest.get("name");
                if (name instanceof String value && !value.isBlank()) return value;
            }
        }
        return "Consumidor final";
    }

    private static String itemBlockedReason(
            Sale sale,
            boolean cashShiftRequirementSatisfied,
            BigDecimal returnable,
            boolean traceSafe) {
        if (sale.getSourceOrderId() != null) {
            return "Las ventas POS diferidas no admiten devoluciones.";
        }
        if (sale.getStatus() != SaleStatus.completed
                && sale.getStatus() != SaleStatus.partially_returned) {
            return "El estado de la venta no admite devoluciones.";
        }
        if (!cashShiftRequirementSatisfied) {
            return "Se requiere un turno de caja abierto para devolver el componente en efectivo.";
        }
        if (returnable.signum() <= 0) return "La linea ya fue devuelta completamente.";
        if (!traceSafe) return "El historial de inventario de la linea es inconsistente.";
        return "La linea no admite devolucion.";
    }

    private static String overallReturnBlockedReason(
            Sale sale,
            boolean cashShiftRequirementSatisfied,
            List<SaleReturnEligibilityResponse.Item> items) {
        if (sale.getSourceOrderId() != null) {
            return "Las ventas POS diferidas no admiten devoluciones.";
        }
        if (sale.getStatus() != SaleStatus.completed
                && sale.getStatus() != SaleStatus.partially_returned) {
            return "El estado de la venta no admite devoluciones.";
        }
        if (!cashShiftRequirementSatisfied) {
            return "Se requiere un turno de caja abierto para devolver el componente en efectivo.";
        }
        if (items.stream().allMatch(item -> item.returnableQuantity().signum() <= 0)) {
            return "La venta ya fue devuelta completamente.";
        }
        return "No existe una linea con inventario retornable verificable.";
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

    private record ReturnExecutionResult(
            SaleReturnResponse response,
            List<UUID> inventoryMovementIds,
            List<UUID> cashMovementIds,
            BigDecimal cashMovementAmount) {}

    private record TraceEligibility(
            boolean safe,
            List<SaleReturnEligibilityResponse.TraceOption> options) {}

    private record TraceOptionKey(UUID locationId, UUID lotId, String lotNumber)
            implements Comparable<TraceOptionKey> {
        @Override
        public int compareTo(TraceOptionKey other) {
            int location = compareNullable(locationId, other.locationId);
            if (location != 0) return location;
            int lot = compareNullable(lotId, other.lotId);
            if (lot != 0) return lot;
            return compareNullable(lotNumber, other.lotNumber);
        }

        private static <T extends Comparable<? super T>> int compareNullable(T left, T right) {
            if (left == right) return 0;
            if (left == null) return -1;
            if (right == null) return 1;
            return left.compareTo(right);
        }
    }

    private record TraceKey(UUID locationId, UUID lotId) {}

    private record TraceIdentity(UUID locationId, UUID lotId) {}
}
