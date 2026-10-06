package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.repository.BusinessCapabilitiesConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.catalog.service.ProductKitService;
import com.omniretail.backend.catalog.service.KitFulfillmentSnapshot;
import com.omniretail.backend.catalog.service.ProductUnitConversionResolver;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.service.OrderEmailNotifier;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderItemRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.InventoryHistoricalTraceDetail;
import com.omniretail.backend.inventory.dto.InventoryOutboundCommand;
import com.omniretail.backend.inventory.dto.InventoryRestoreCommand;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityHistoryService;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.dto.CashMovementResponse;
import com.omniretail.backend.pos.dto.CreateSaleRequest;
import com.omniretail.backend.pos.dto.InventoryTrackingDetailResponse;
import com.omniretail.backend.pos.dto.InventoryTrackingSelectionRequest;
import com.omniretail.backend.pos.dto.PaymentResponse;
import com.omniretail.backend.pos.dto.PosDeferredOrderResponse;
import com.omniretail.backend.pos.dto.PosPickingOrderResponse;
import com.omniretail.backend.pos.dto.SaleConfirmationResponse;
import com.omniretail.backend.pos.dto.SaleDetailResponse;
import com.omniretail.backend.pos.dto.SaleItemResponse;
import com.omniretail.backend.pos.dto.SaleResponse;
import com.omniretail.backend.pos.entity.CashMovement;
import com.omniretail.backend.pos.entity.CashMovementType;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import com.omniretail.backend.pos.entity.Payment;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.pos.entity.PaymentStatus;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleDocumentType;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.pos.repository.CashMovementRepository;
import com.omniretail.backend.pos.repository.CashShiftRepository;
import com.omniretail.backend.pos.repository.PaymentRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import com.omniretail.backend.shared.validation.PhoneNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
@RequiredArgsConstructor
public class SaleService {
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard capability;
    private final BranchAccessResolver branchAccess;
    private final CashShiftRepository shifts;
    private final ProductRepository products;
    private final TenantRepository tenants;
    private final CustomerRepository customers;
    private final BusinessCapabilitiesConfigRepository businessConfig;
    private final BankAccountRepository bankAccounts;
    private final SaleRepository sales;
    private final SaleItemRepository items;
    private final PaymentRepository payments;
    private final CashMovementRepository cashMovements;
    private final InventoryStockService inventory;
    private final InventoryTraceabilityMutationService traceabilityMutation;
    private final InventoryTraceabilityHistoryService traceabilityHistory;
    private final InventoryMovementRepository inventoryMovements;
    private final DocumentCounterService counter;
    private final ProductPriceResolver productPriceResolver;
    private final ProductKitService productKitService;
    private final ProductUnitConversionResolver unitConversionResolver;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final InventoryReservationRepository reservations;
    private final InventoryReservationLifecycleService reservationLifecycle;
    private final PickingService pickingService;
    private final PickingOrderRepository pickingOrders;
    private final JsonMapper jsonMapper;
    private final OrderEmailNotifier orderEmailNotifier;

    public SaleConfirmationResponse create(CreateSaleRequest request) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (request.confirmationId() == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "CONFIRMATION_ID_REQUIRED",
                    "La confirmación de la venta es obligatoria.");
        }
        NormalizedDocument document = normalizeDocument(request.document());
        if (request.sourceOrderId() != null && request.deferredOrder() != null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "SALE_ORDER_INPUT_CONFLICT",
                    "La venta no puede recibir sourceOrderId y deferredOrder simultaneamente.");
        }
        if (request.sourceOrderId() != null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "SOURCE_ORDER_SALE_NOT_SUPPORTED",
                    "La confirmacion de una Order existente no forma parte de este incremento.");
        }
        NormalizedDeferredOrder deferredOrder = normalizeDeferredOrder(request.deferredOrder());
        if (!branchAccess.resolve(actor).allows(request.branchId())) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        if (request.customerId() != null
                && customers.findByTenantIdAndId(actor.tenantId(), request.customerId()).isEmpty()) {
            throw notFound("CUSTOMER_NOT_FOUND", "Cliente no encontrado.");
        }
        var shift = shifts.findOwnedByIdForUpdate(
                        actor.tenantId(), actor.userId(), request.cashShiftId())
                .filter(found -> found.getBranchId().equals(request.branchId())
                        && found.getStatus() == CashShiftStatus.open)
                .orElseThrow(() -> notFound("CASH_SHIFT_NOT_FOUND", "Turno de caja no encontrado."));
        var existing = sales.findByTenantIdAndConfirmationId(actor.tenantId(), request.confirmationId());
        if (existing.isPresent()) {
            String currentFingerprint = fingerprint(request, document);
            if (existing.get().getConfirmationFingerprint() != null
                    && !existing.get().getConfirmationFingerprint().equals(currentFingerprint)
                    && !matchesLegacyConfirmation(existing.get(), request, document)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "La confirmación ya fue usada con una venta distinta.");
            }
            return confirmationResponse(actor.tenantId(), existing.get(), true);
        }
        Tenant tenant = tenants.findById(actor.tenantId())
                .orElseThrow(() -> notFound("TENANT_NOT_FOUND", "Negocio no encontrado."));
        Set<UUID> productIds = new HashSet<>();
        Instant pricingAt = Instant.now();
        List<LinePricing> catalog = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (var line : request.items()) {
            if (!productIds.add(line.productId())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "DUPLICATE_PRODUCT",
                        "No se permiten productos repetidos en una venta.");
            }
            Product product = products.findByTenantIdAndId(actor.tenantId(), line.productId())
                    .filter(found -> found.getStatus() == ProductStatus.published
                            && Boolean.TRUE.equals(found.getChannelPos()))
                    .orElseThrow(() -> notFound("PRODUCT_NOT_FOUND", "Producto no encontrado o no disponible para POS."));
            if (deferredOrder != null) {
                validateDeferredProduct(product, line);
            }
            ResolvedProductPrice resolved = productPriceResolver.resolveEffectivePrice(
                    actor.tenantId(), product, pricingAt, "pos", request.branchId(), line.quantity());
            BigDecimal gross = money(resolved.basePrice().multiply(line.quantity()));
            BigDecimal manualDiscount = money(line.discount() == null ? BigDecimal.ZERO : line.discount());
            if (manualDiscount.compareTo(gross) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT", "El descuento supera el importe de la línea.");
            }
            BigDecimal promotionDiscount = money(resolved.discountAmount().multiply(line.quantity()));
            boolean usePromotion = resolved.promotionId() != null
                    && promotionDiscount.compareTo(manualDiscount) >= 0;
            BigDecimal lineDiscount = usePromotion ? promotionDiscount : manualDiscount;
            if (lineDiscount.compareTo(gross) > 0) {
                lineDiscount = gross;
            }
            BigDecimal lineSubtotal = money(gross.subtract(lineDiscount));
            BigDecimal inventoryQuantity = product.getProductType() == ProductType.physical
                            && Boolean.TRUE.equals(product.getTrackingStock())
                    ? unitConversionResolver.toBaseQuantity(actor.tenantId(), product, line.quantity())
                    : null;
            catalog.add(new LinePricing(product, resolved.basePrice(), inventoryQuantity,
                    lineDiscount, lineSubtotal, usePromotion ? resolved.promotionId() : null));
            subtotal = subtotal.add(lineSubtotal).setScale(2, RoundingMode.HALF_UP);
            discount = discount.add(lineDiscount).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        validatePayments(actor, request);
        BigDecimal paid = request.payments().stream().map(CreateSaleRequest.PaymentLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(total) != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_TOTAL_MISMATCH", "Los pagos deben coincidir con el total.");
        }
        String saleNumber = counter.nextPosSaleNumber(actor.tenantId());
        DeferredFulfillment deferredFulfillment = deferredOrder == null
                ? null
                : createDeferredFulfillment(
                        actor, request, deferredOrder, catalog, saleNumber,
                        subtotal, discount, total, fingerprint(request, document));
        Sale newSale = Sale.builder().branchId(request.branchId()).cashShiftId(shift.getId())
                .createdByUserId(actor.userId()).number(saleNumber)
                .customerId(request.customerId()).confirmationId(request.confirmationId())
                .confirmationFingerprint(fingerprint(request, document))
                .sourceOrderId(deferredFulfillment == null
                        ? null : deferredFulfillment.order().getId())
                .documentType(document.type()).documentTaxId(document.taxId())
                .documentLegalName(document.legalName()).documentFiscalAddress(document.fiscalAddress())
                .subtotal(subtotal)
                .discountTotal(discount).taxTotal(tax).total(total).build();
        newSale.setTenantId(actor.tenantId());
        Sale sale = sales.saveAndFlush(newSale);
        List<SaleItem> savedItems = new ArrayList<>();
        List<SaleInventoryPlan> inventoryPlans = new ArrayList<>();
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            LinePricing pricing = catalog.get(index);
            Product product = pricing.product();
            List<ProductKitService.FulfillmentComponent> fulfillment = product.getProductType() == ProductType.kit
                    ? productKitService.fulfillment(actor.tenantId(), product, line.quantity()) : List.of();
            SaleItem saleItem = items.saveAndFlush(SaleItem.builder()
                    .saleId(sale.getId()).productId(product.getId()).skuSnapshot(product.getSku())
                    .nameSnapshot(product.getName()).quantity(line.quantity()).unitPrice(pricing.unitPrice())
                    .discount(pricing.discount()).subtotal(pricing.subtotal())
                    .promotionId(pricing.promotionId())
                    .fulfillmentComponents(KitFulfillmentSnapshot.encode(fulfillment)).build());
            savedItems.add(saleItem);
            if (deferredFulfillment == null) {
                inventoryPlans.addAll(saleInventoryPlans(
                        actor.tenantId(), saleItem, product, pricing.inventoryQuantity(), fulfillment,
                        line.trackingSelections()));
            }
        }
        List<InventoryMovement> createdInventoryMovements = inventoryPlans.stream()
                .sorted(Comparator.comparing((SaleInventoryPlan plan) -> plan.product().getId())
                        .thenComparing(SaleInventoryPlan::locationId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(plan -> plan.saleItem().getId()))
                .map(plan -> consumeSaleInventory(actor, sale, plan))
                .toList();
        List<Payment> savedPayments = new ArrayList<>();
        BigDecimal cashAmount = BigDecimal.ZERO.setScale(2);
        for (var paymentRequest : request.payments()) {
            UUID bankAccountId = paymentRequest.method() == PaymentMethod.transfer
                    ? paymentRequest.bankAccountId()
                    : null;
            Boolean externallyVerified = paymentRequest.method() == PaymentMethod.transfer
                    ? paymentRequest.externallyVerified()
                    : null;
            Payment payment = Payment.builder().saleId(sale.getId())
                    .orderId(deferredFulfillment == null
                            ? null : deferredFulfillment.order().getId())
                    .method(paymentRequest.method())
                    .status(PaymentStatus.approved).amount(paymentRequest.amount().setScale(2, RoundingMode.HALF_UP))
                    .currency(tenant.getDefaultCurrency()).bankAccountId(bankAccountId)
                    .reference(paymentRequest.reference()).externallyVerified(externallyVerified)
                    .verifiedByUserId(externallyVerified == null ? null : actor.userId())
                    .verifiedAt(externallyVerified == null ? null : Instant.now())
                    .build();
            payment.setTenantId(actor.tenantId());
            payments.save(payment);
            savedPayments.add(payment);
            if (paymentRequest.method() == PaymentMethod.cash) {
                cashAmount = cashAmount.add(paymentRequest.amount()).setScale(2, RoundingMode.HALF_UP);
            }
        }
        CashMovement cashMovement = null;
        if (cashAmount.signum() > 0) {
            cashMovement = CashMovement.builder().tenantId(actor.tenantId()).cashShiftId(shift.getId())
                    .type(CashMovementType.in).amount(cashAmount)
                    .reason("Venta POS #" + sale.getNumber()).referenceType("sale").referenceId(sale.getId())
                    .createdByUserId(actor.userId()).build();
            cashMovements.save(cashMovement);
        }
        return confirmationResponse(
                sale, savedItems, savedPayments, createdInventoryMovements, cashMovement,
                deferredFulfillment, false);
    }

    private DeferredFulfillment createDeferredFulfillment(
            AuthenticatedUser actor,
            CreateSaleRequest request,
            NormalizedDeferredOrder deferred,
            List<LinePricing> catalog,
            String saleNumber,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal total,
            String confirmationFingerprint) {
        if (orders.findByTenantIdAndSourceAndIdempotencyKey(
                        actor.tenantId(), OrderSource.pos, deferred.idempotencyKey())
                .isPresent()) {
            throw BusinessException.conflict(
                    "ORDER_IDEMPOTENCY_KEY_REUSED",
                    "La llave de idempotencia del pedido ya fue utilizada.");
        }

        Order order = Order.builder()
                .branchId(request.branchId())
                .orderNumber(saleNumber)
                .source(OrderSource.pos)
                .customerId(request.customerId())
                .status(OrderStatus.confirmed)
                .deliveryMethod(deferred.deliveryMethod())
                .transportMode(deferred.transportMode())
                .deliveryAddress(json(deferred.deliveryAddress()))
                .notificationContact(json(deferred.notificationContact()))
                .subtotal(subtotal)
                .discountTotal(discount)
                .shippingTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .total(total)
                .trackingToken("pos-" + deferred.idempotencyKey())
                .idempotencyKey(deferred.idempotencyKey())
                .idempotencyFingerprint(confirmationFingerprint)
                .build();
        order.setTenantId(actor.tenantId());
        Order savedOrder = orders.saveAndFlush(order);
        orderEmailNotifier.orderConfirmed(savedOrder);

        List<OrderItem> savedOrderItems = new ArrayList<>();
        List<DeferredReservationPlan> reservationPlans = new ArrayList<>();
        for (int index = 0; index < request.items().size(); index++) {
            CreateSaleRequest.Item line = request.items().get(index);
            LinePricing pricing = catalog.get(index);
            Product product = pricing.product();
            OrderItem orderItem = OrderItem.builder()
                    .orderId(savedOrder.getId())
                    .productId(product.getId())
                    .promotionId(pricing.promotionId())
                    .skuSnapshot(product.getSku())
                    .nameSnapshot(product.getName())
                    .quantity(line.quantity())
                    .inventoryQuantity(pricing.inventoryQuantity())
                    .unitPrice(pricing.unitPrice())
                    .discount(pricing.discount())
                    .subtotal(pricing.subtotal())
                    .fulfillmentComponents(null)
                    .build();
            OrderItem savedItem = orderItems.saveAndFlush(orderItem);
            savedOrderItems.add(savedItem);
            if (pricing.inventoryQuantity() != null) {
                reservationPlans.add(new DeferredReservationPlan(
                        product, savedItem, pricing.inventoryQuantity()));
            }
        }
        reservationPlans.stream()
                .sorted(Comparator.comparing((DeferredReservationPlan plan) ->
                                plan.product().getId())
                        .thenComparing(plan -> plan.orderItem().getId()))
                .forEach(plan -> reservationLifecycle.reserve(new ReserveInventoryCommand(
                        actor.tenantId(),
                        request.branchId(),
                        plan.product().getId(),
                        InventoryReservationSourceType.order,
                        savedOrder.getId(),
                        plan.orderItem().getId(),
                        savedOrder.getId(),
                        plan.orderItem().getId(),
                        plan.quantity())));

        PickingOrder picking = pickingService.ensureForOrder(actor.tenantId(), savedOrder.getId())
                .orElseThrow(() -> BusinessException.conflict(
                        "DEFERRED_ORDER_REQUIRES_PICKING",
                        "La venta diferida requiere al menos una linea fisica para Picking."));
        return new DeferredFulfillment(savedOrder, List.copyOf(savedOrderItems), picking);
    }

    private String json(Object value) {
        return value == null ? null : jsonMapper.writeValueAsString(value);
    }

    private static void validateDeferredProduct(Product product, CreateSaleRequest.Item line) {
        if (product.getProductType() == ProductType.kit) {
            throw BusinessException.conflict(
                    "KIT_FULFILLMENT_NOT_SUPPORTED",
                    "Los kits aun no admiten fulfillment POS diferido.");
        }
        if (Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial())
                || Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw BusinessException.conflict(
                    "TRACEABILITY_NOT_SUPPORTED",
                    "El fulfillment POS diferido aun no admite productos trazables.");
        }
        if (line.trackingSelections() != null && !line.trackingSelections().isEmpty()) {
            throw invalidTrackingSelection(
                    "La seleccion fisica de una venta diferida corresponde a Picking.");
        }
    }

    private List<SaleInventoryPlan> saleInventoryPlans(
            UUID tenantId,
            SaleItem saleItem,
            Product soldProduct,
            BigDecimal physicalQuantity,
            List<ProductKitService.FulfillmentComponent> fulfillment,
            List<InventoryTrackingSelectionRequest> requestedSelections) {
        List<InventoryTrackingSelectionRequest> selections = requestedSelections == null
                ? List.of()
                : requestedSelections;
        if (fulfillment.isEmpty()) {
            selections.forEach(selection -> {
                if (selection == null || !soldProduct.getId().equals(selection.productId())) {
                    throw invalidTrackingSelection(
                            "La seleccion trazable no corresponde al producto vendido.");
                }
            });
            if (soldProduct.getProductType() != ProductType.physical
                    || !Boolean.TRUE.equals(soldProduct.getTrackingStock())) {
                if (!selections.isEmpty()) {
                    throw invalidTrackingSelection(
                            "El producto no utiliza inventario trazable.");
                }
                return List.of();
            }
            return plansForInventoryProduct(
                    saleItem, soldProduct, physicalQuantity, selections, false);
        }

        Set<UUID> componentIds = fulfillment.stream()
                .map(ProductKitService.FulfillmentComponent::productId)
                .collect(Collectors.toSet());
        for (InventoryTrackingSelectionRequest selection : selections) {
            if (selection == null || !componentIds.contains(selection.productId())) {
                throw invalidTrackingSelection(
                        "La seleccion trazable no corresponde a un componente del kit.");
            }
        }
        List<SaleInventoryPlan> plans = new ArrayList<>();
        for (ProductKitService.FulfillmentComponent component : fulfillment) {
            Product componentProduct = products.findByTenantIdAndId(tenantId, component.productId())
                    .orElseThrow(() -> notFound("PRODUCT_NOT_FOUND", "Componente de kit no encontrado."));
            List<InventoryTrackingSelectionRequest> componentSelections = selections.stream()
                    .filter(selection -> component.productId().equals(selection.productId()))
                    .toList();
            plans.addAll(plansForInventoryProduct(
                    saleItem, componentProduct, component.quantity(), componentSelections, true));
        }
        return plans;
    }

    private static List<SaleInventoryPlan> plansForInventoryProduct(
            SaleItem saleItem,
            Product product,
            BigDecimal requiredQuantity,
            List<InventoryTrackingSelectionRequest> selections,
            boolean kitComponent) {
        boolean traceable = isTraceable(product);
        if (!traceable) {
            if (!selections.isEmpty()) {
                throw invalidTrackingSelection(
                        "El producto no utiliza trazabilidad por lote o serie.");
            }
            return List.of(new SaleInventoryPlan(
                    saleItem, product, null, requiredQuantity, List.of(), kitComponent, false));
        }
        if (selections.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_SELECTIONS_REQUIRED",
                    "El producto trazable requiere seleccion explicita de inventario.");
        }
        if (selections.stream().anyMatch(selection -> selection.locationId() == null)) {
            throw invalidTrackingSelection(
                    "La ubicacion es requerida para cada seleccion trazable.");
        }
        BigDecimal selected = selections.stream()
                .map(InventoryTrackingSelectionRequest::quantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (selected.compareTo(requiredQuantity) != 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La seleccion trazable no coincide con la cantidad fisica requerida.");
        }
        Set<String> serials = new HashSet<>();
        selections.stream()
                .flatMap(selection -> safeSerials(selection.serialNumbers()).stream())
                .forEach(serial -> {
                    String normalized = serial == null ? null : serial.trim();
                    if (normalized == null || normalized.isEmpty() || !serials.add(normalized)) {
                        throw BusinessException.conflict(
                                "DUPLICATE_SERIAL",
                                "Un numero de serie no puede repetirse entre selecciones.");
                    }
                });
        Map<UUID, List<InventoryTrackingSelectionRequest>> byLocation = selections.stream()
                .collect(Collectors.groupingBy(InventoryTrackingSelectionRequest::locationId));
        return byLocation.entrySet().stream()
                .map(entry -> new SaleInventoryPlan(
                        saleItem,
                        product,
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(InventoryTrackingSelectionRequest::quantity)
                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                        entry.getValue().stream()
                                .map(selection -> new InventoryTraceabilitySelection(
                                        selection.lotId(),
                                        selection.quantity(),
                                        safeSerials(selection.serialNumbers())))
                                .toList(),
                        kitComponent,
                        true))
                .toList();
    }

    private InventoryMovement consumeSaleInventory(
            AuthenticatedUser actor, Sale sale, SaleInventoryPlan plan) {
        String referenceType = plan.kitComponent() ? "POS_KIT_SALE" : "POS_SALE";
        String reason = (plan.kitComponent() ? "Venta kit POS #" : "Venta POS #")
                + sale.getNumber();
        if (!plan.traceable()) {
            return inventory.deductStock(new DeductStockCommand(
                    actor.tenantId(),
                    sale.getBranchId(),
                    plan.product().getId(),
                    plan.quantity(),
                    reason,
                    referenceType,
                    sale.getId(),
                    plan.saleItem().getId(),
                    actor.userId()));
        }
        return traceabilityMutation.consume(new InventoryOutboundCommand(
                actor.tenantId(),
                sale.getBranchId(),
                plan.product(),
                plan.locationId(),
                plan.quantity(),
                plan.selections(),
                reason,
                referenceType,
                sale.getId(),
                plan.saleItem().getId(),
                actor.userId()));
    }

    @Transactional(readOnly = true)
    public Page<SaleResponse> list(UUID branchId, SaleStatus status, Instant from, Instant to, Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        if (!branchAccess.resolve(actor).allows(branchId)) {
            throw notFound("BRANCH_NOT_FOUND", "Sucursal no encontrada.");
        }
        if ((from == null) != (to == null) || (from != null && from.isAfter(to))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SALE_DATE_RANGE",
                    "Las fechas from y to son requeridas juntas y deben formar un rango válido.");
        }
        Page<Sale> page;
        if (from != null && to != null) {
            page = status == null
                    ? sales.findByTenantIdAndBranchIdAndCreatedAtBetween(actor.tenantId(), branchId, from, to, pageable)
                    : sales.findByTenantIdAndBranchIdAndStatusAndCreatedAtBetween(actor.tenantId(), branchId, status, from, to, pageable);
        } else {
            page = status == null
                    ? sales.findByTenantIdAndBranchId(actor.tenantId(), branchId, pageable)
                    : sales.findByTenantIdAndBranchIdAndStatus(actor.tenantId(), branchId, status, pageable);
        }
        return page.map(SaleResponse::from);
    }

    @Transactional(readOnly = true)
    public SaleDetailResponse get(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        Sale sale = sales.findByTenantIdAndId(actor.tenantId(), id)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branchAccess.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        List<SaleItem> saleItems = items.findByTenantIdAndSaleId(actor.tenantId(), id);
        List<InventoryMovement> movements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        actor.tenantId(), Set.of("POS_SALE", "POS_KIT_SALE"), id);
        Map<UUID, List<InventoryHistoricalTraceDetail>> byMovement =
                traceabilityHistory.expand(actor.tenantId(), movements);
        Map<UUID, List<InventoryTrackingDetailResponse>> trackingByLine = trackingByLine(
                movements, byMovement);
        return new SaleDetailResponse(
                SaleResponse.from(sale),
                saleItems.stream()
                        .map(item -> SaleItemResponse.from(
                                item, trackingByLine.getOrDefault(item.getId(), List.of())))
                        .toList(),
                payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), id)
                        .stream().map(PaymentResponse::from).toList());
    }

    public SaleResponse voidSale(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        capability.ensureTenantCapability(actor.tenantId(), SaasCapability.pos);
        Sale sale = sales.findByTenantIdAndIdForUpdate(actor.tenantId(), id)
                .orElseThrow(() -> notFound("SALE_NOT_FOUND", "Venta no encontrada."));
        if (!branchAccess.resolve(actor).allows(sale.getBranchId())) {
            throw notFound("SALE_NOT_FOUND", "Venta no encontrada.");
        }
        if (sale.getStatus() == SaleStatus.cancelled) {
            throw new BusinessException(HttpStatus.CONFLICT, "SALE_ALREADY_VOIDED", "La venta ya está anulada.");
        }
        if (sale.getStatus() != SaleStatus.completed) {
            throw new BusinessException(HttpStatus.CONFLICT, "SALE_NOT_VOIDABLE",
                    "Una venta con devoluciones no puede anularse.");
        }
        if (sale.getSourceOrderId() != null) {
            cancelDeferredOrder(actor.tenantId(), sale);
        } else {
            List<SaleItem> saleItems = items.findByTenantIdAndSaleId(actor.tenantId(), id);
            List<InventoryMovement> originalMovements = inventoryMovements
                    .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                            actor.tenantId(), Set.of("POS_SALE", "POS_KIT_SALE"), sale.getId());
            Map<UUID, List<InventoryHistoricalTraceDetail>> originalHistory =
                    traceabilityHistory.expand(actor.tenantId(), originalMovements);
            List<SaleRestorePlan> restorePlans = new ArrayList<>();
            for (SaleItem item : saleItems) {
                restorePlans.addAll(voidPlans(
                        actor.tenantId(), item, originalMovements, originalHistory));
            }
            restorePlans.stream()
                    .sorted(Comparator.comparing((SaleRestorePlan plan) -> plan.product().getId())
                            .thenComparing(SaleRestorePlan::locationId,
                                    Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(plan -> plan.saleItem().getId()))
                    .forEach(plan -> restoreVoidedInventory(actor, sale, plan));
        }
        var shift = shifts.findByTenantIdAndId(actor.tenantId(), sale.getCashShiftId())
                .filter(found -> found.getStatus() == CashShiftStatus.open);
        if (shift.isPresent()) {
            payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(), id).stream()
                    .filter(payment -> payment.getMethod() == PaymentMethod.cash)
                    .forEach(payment -> cashMovements.save(CashMovement.builder().tenantId(actor.tenantId())
                            .cashShiftId(sale.getCashShiftId()).type(CashMovementType.out).amount(payment.getAmount())
                            .reason("Anulación venta POS #" + sale.getNumber()).referenceType("sale_void")
                            .referenceId(sale.getId()).createdByUserId(actor.userId()).build()));
        }
        sale.setStatus(SaleStatus.cancelled);
        return SaleResponse.from(sales.save(sale));
    }

    private void cancelDeferredOrder(UUID tenantId, Sale sale) {
        Order order = orders.findByTenantIdAndIdForUpdate(tenantId, sale.getSourceOrderId())
                .filter(found -> found.getBranchId().equals(sale.getBranchId())
                        && found.getSource() == OrderSource.pos
                        && isSupportedDeferredDeliveryMethod(found.getDeliveryMethod()))
                .orElseThrow(SaleService::incompleteDeferredConfirmation);
        if (order.getStatus() == OrderStatus.dispatched
                || order.getStatus() == OrderStatus.delivered) {
            throw BusinessException.conflict(
                    "DEFERRED_SALE_ALREADY_FULFILLED",
                    "La venta diferida ya consumio inventario y no puede anularse de forma segura.");
        }
        List<InventoryReservation> orderReservations =
                reservations.findByTenantIdAndOrderId(tenantId, order.getId());
        if (orderReservations.stream().anyMatch(reservation ->
                reservation.getStatus() == InventoryReservationStatus.consumed)) {
            throw BusinessException.conflict(
                    "DEFERRED_SALE_ALREADY_FULFILLED",
                    "La venta diferida ya consumio inventario y no puede anularse de forma segura.");
        }
        for (InventoryReservation reservation : orderReservations) {
            if (reservation.getStatus() == InventoryReservationStatus.active) {
                reservationLifecycle.release(tenantId, reservation.getId());
            }
        }
        order.setStatus(OrderStatus.cancelled);
        orders.save(order);
    }

    private List<SaleRestorePlan> voidPlans(
            UUID tenantId,
            SaleItem saleItem,
            List<InventoryMovement> originalMovements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> history) {
        List<InventoryMovement> lineMovements = originalMovements.stream()
                .filter(movement -> saleItem.getId().equals(movement.getReferenceLineId()))
                .toList();
        Map<UUID, ExpectedInventory> expected = expectedInventory(tenantId, saleItem);
        if (lineMovements.isEmpty()) {
            List<SaleRestorePlan> legacy = new ArrayList<>();
            for (ExpectedInventory value : expected.values()) {
                List<InventoryMovement> legacyMovements = value.kitComponent()
                        ? List.of()
                        : originalMovements.stream()
                                .filter(movement -> movement.getReferenceLineId() == null)
                                .filter(movement -> "POS_SALE".equals(movement.getReferenceType()))
                                .filter(movement -> value.product().getId().equals(movement.getProductId()))
                                .toList();
                if (!legacyMovements.isEmpty()) {
                    for (InventoryMovement movement : legacyMovements) {
                        List<InventoryHistoricalTraceDetail> details =
                                history.getOrDefault(movement.getId(), List.of());
                        boolean traceable = !details.isEmpty();
                        if (traceable != isTraceable(value.product())) throw inconsistentSaleHistory();
                        legacy.add(new SaleRestorePlan(
                                saleItem,
                                value.product(),
                                movement.getFromLocationId(),
                                movement.getQuantity(),
                                details.stream()
                                        .map(detail -> new InventoryTraceabilitySelection(
                                                detail.lotId(), detail.quantity(), detail.serialNumbers()))
                                        .toList(),
                                false,
                                traceable));
                    }
                    continue;
                }
                if (isTraceable(value.product())) {
                    throw inconsistentSaleHistory();
                }
                legacy.add(new SaleRestorePlan(
                        saleItem,
                        value.product(),
                        null,
                        value.quantity(),
                        List.of(),
                        value.kitComponent(),
                        false));
            }
            return legacy;
        }

        List<SaleRestorePlan> plans = new ArrayList<>();
        for (InventoryMovement movement : lineMovements) {
            ExpectedInventory expectedProduct = expected.get(movement.getProductId());
            if (expectedProduct == null) throw inconsistentSaleHistory();
            Product product = expectedProduct.product();
            List<InventoryHistoricalTraceDetail> details =
                    history.getOrDefault(movement.getId(), List.of());
            boolean traceable = !details.isEmpty();
            if (traceable != isTraceable(product)) throw inconsistentSaleHistory();
            plans.add(new SaleRestorePlan(
                    saleItem,
                    product,
                    movement.getFromLocationId(),
                    movement.getQuantity(),
                    details.stream()
                            .map(detail -> new InventoryTraceabilitySelection(
                                    detail.lotId(), detail.quantity(), detail.serialNumbers()))
                            .toList(),
                    expectedProduct.kitComponent(),
                    traceable));
        }
        return plans;
    }

    private Map<UUID, ExpectedInventory> expectedInventory(UUID tenantId, SaleItem saleItem) {
        List<KitFulfillmentSnapshot.Component> fulfillment =
                KitFulfillmentSnapshot.decode(saleItem.getFulfillmentComponents());
        Map<UUID, ExpectedInventory> expected = new HashMap<>();
        if (fulfillment.isEmpty()) {
            Product product = products.findByTenantIdAndId(tenantId, saleItem.getProductId())
                    .orElseThrow(SaleService::inconsistentSaleHistory);
            if (product.getProductType() == ProductType.physical
                    && Boolean.TRUE.equals(product.getTrackingStock())) {
                expected.put(product.getId(), new ExpectedInventory(product, saleItem.getQuantity(), false));
            }
            return expected;
        }
        for (KitFulfillmentSnapshot.Component component : fulfillment) {
            Product product = products.findByTenantIdAndId(tenantId, component.productId())
                    .orElseThrow(SaleService::inconsistentSaleHistory);
            expected.put(product.getId(), new ExpectedInventory(
                    product,
                    saleItem.getQuantity().multiply(component.quantityPerKit()),
                    true));
        }
        return expected;
    }

    private void restoreVoidedInventory(
            AuthenticatedUser actor, Sale sale, SaleRestorePlan plan) {
        String referenceType = plan.kitComponent()
                ? "POS_KIT_SALE_VOID"
                : "POS_SALE_VOID";
        String reason = (plan.kitComponent() ? "Anulacion venta kit POS #" : "Anulación venta POS #")
                + sale.getNumber();
        if (!plan.traceable()) {
            inventory.incrementStock(new AddStockCommand(
                    actor.tenantId(),
                    sale.getBranchId(),
                    plan.product().getId(),
                    plan.quantity(),
                    reason,
                    referenceType,
                    sale.getId(),
                    plan.saleItem().getId(),
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
                sale.getId(),
                plan.saleItem().getId(),
                actor.userId()));
    }

    private static Map<UUID, List<InventoryTrackingDetailResponse>> trackingByLine(
            List<InventoryMovement> movements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> history) {
        Map<UUID, List<InventoryTrackingDetailResponse>> result = new HashMap<>();
        for (InventoryMovement movement : movements) {
            if (movement.getReferenceLineId() == null) continue;
            List<InventoryTrackingDetailResponse> values = result.computeIfAbsent(
                    movement.getReferenceLineId(), ignored -> new ArrayList<>());
            history.getOrDefault(movement.getId(), List.of()).stream()
                    .map(SaleService::trackingResponse)
                    .forEach(values::add);
        }
        return result;
    }

    private static InventoryTrackingDetailResponse trackingResponse(
            InventoryHistoricalTraceDetail detail) {
        return new InventoryTrackingDetailResponse(
                detail.productId(),
                detail.locationId(),
                detail.lotId(),
                detail.lotNumber(),
                detail.quantity(),
                detail.serialNumbers());
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private void validatePayments(AuthenticatedUser actor, CreateSaleRequest request) {
        var config = businessConfig.findByTenantId(actor.tenantId()).orElse(null);
        for (CreateSaleRequest.PaymentLine payment : request.payments()) {
            if (payment.amount().signum() <= 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_AMOUNT_INVALID",
                        "El monto del pago debe ser mayor que cero.");
            }
            if (config != null && config.getAllowedPosPaymentMethods() != null
                    && !config.getAllowedPosPaymentMethods().isEmpty()
                    && !config.getAllowedPosPaymentMethods().contains(payment.method().name())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_METHOD_NOT_ALLOWED",
                        "Método de pago no permitido.");
            }
            if (payment.method() == PaymentMethod.card
                    && (payment.reference() == null || payment.reference().isBlank())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_REFERENCE_REQUIRED",
                        "La tarjeta requiere referencia.");
            }
            if (payment.method() == PaymentMethod.transfer) {
                if (payment.bankAccountId() == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "BANK_ACCOUNT_INVALID",
                            "La cuenta bancaria no es válida para la sucursal.");
                }
                if (payment.reference() == null || payment.reference().isBlank()) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "PAYMENT_REFERENCE_REQUIRED",
                            "La transferencia requiere el número de comprobante.");
                }
                if (payment.externallyVerified() == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "EXTERNAL_VERIFICATION_REQUIRED",
                            "La transferencia requiere indicar su verificación externa.");
                }
                boolean validAccount = bankAccounts.findByTenantIdAndId(actor.tenantId(), payment.bankAccountId())
                        .filter(account -> account.getStatus() == BankAccountStatus.active)
                        .filter(account -> account.getBranchIds().contains(request.branchId()))
                        .isPresent();
                if (!validAccount) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "BANK_ACCOUNT_INVALID",
                            "La cuenta bancaria no es válida para la sucursal.");
                }
            }
        }
    }

    private SaleConfirmationResponse confirmationResponse(UUID tenantId, Sale sale, boolean idempotent) {
        List<SaleItem> saleItems = items.findByTenantIdAndSaleId(tenantId, sale.getId());
        List<Payment> salePayments = payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(
                tenantId, sale.getId());
        List<InventoryMovement> movements = inventoryMovements
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        tenantId, List.of("POS_SALE", "POS_KIT_SALE"), sale.getId());
        CashMovement cashMovement = cashMovements
                .findFirstByTenantIdAndReferenceTypeAndReferenceIdOrderByCreatedAtAscIdAsc(
                        tenantId, "sale", sale.getId())
                .orElse(null);
        DeferredFulfillment deferred = null;
        if (sale.getSourceOrderId() != null) {
            Order order = orders.findByTenantIdAndId(tenantId, sale.getSourceOrderId())
                    .filter(found -> found.getBranchId().equals(sale.getBranchId())
                            && found.getSource() == OrderSource.pos
                            && isSupportedDeferredDeliveryMethod(found.getDeliveryMethod()))
                    .orElseThrow(SaleService::incompleteDeferredConfirmation);
            PickingOrder picking = pickingOrders.findByTenantIdAndSourceTypeAndSourceId(
                            tenantId,
                            com.omniretail.backend.logistics.entity.PickingSourceType.order,
                            order.getId())
                    .orElseThrow(SaleService::incompleteDeferredConfirmation);
            deferred = new DeferredFulfillment(
                    order, orderItems.findByOrderId(order.getId()), picking);
        }
        return confirmationResponse(
                sale, saleItems, salePayments, movements, cashMovement, deferred, idempotent);
    }

    private SaleConfirmationResponse confirmationResponse(
            Sale sale,
            List<SaleItem> saleItems,
            List<Payment> salePayments,
            List<InventoryMovement> movements,
            CashMovement cashMovement,
            boolean idempotent) {
        return confirmationResponse(
                sale, saleItems, salePayments, movements, cashMovement, null, idempotent);
    }

    private SaleConfirmationResponse confirmationResponse(
            Sale sale,
            List<SaleItem> saleItems,
            List<Payment> salePayments,
            List<InventoryMovement> movements,
            CashMovement cashMovement,
            DeferredFulfillment deferred,
            boolean idempotent) {
        Map<UUID, List<InventoryHistoricalTraceDetail>> byMovement =
                traceabilityHistory.expand(sale.getTenantId(), movements);
        Map<UUID, List<InventoryTrackingDetailResponse>> trackingByLine =
                trackingByLine(movements, byMovement);
        return SaleConfirmationResponse.from(
                SaleResponse.from(sale),
                saleItems.stream()
                        .map(item -> SaleItemResponse.from(
                                item, trackingByLine.getOrDefault(item.getId(), List.of())))
                        .toList(),
                salePayments.stream().map(PaymentResponse::from).toList(),
                movements.stream().filter(java.util.Objects::nonNull)
                        .map(InventoryMovementResponse::from).toList(),
                cashMovement == null ? null : CashMovementResponse.from(cashMovement),
                deferred == null
                        ? null
                        : PosDeferredOrderResponse.from(
                                deferred.order(), deferred.orderItems(), jsonMapper),
                deferred == null ? null : PosPickingOrderResponse.from(deferred.picking()),
                idempotent);
    }

    private static NormalizedDocument normalizeDocument(CreateSaleRequest.Document input) {
        SaleDocumentType type = input == null ? SaleDocumentType.ticket : input.type();
        if (type == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "SALE_DOCUMENT_TYPE_REQUIRED",
                    "El tipo de documento es requerido.");
        }
        String taxId = trimToNull(input == null ? null : input.taxId());
        String legalName = trimToNull(input == null ? null : input.legalName());
        String fiscalAddress = trimToNull(input == null ? null : input.fiscalAddress());
        if (type == SaleDocumentType.invoice
                && (taxId == null || legalName == null || fiscalAddress == null)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVOICE_FISCAL_DATA_REQUIRED",
                    "La factura requiere NIT, nombre legal y dirección fiscal.");
        }
        if (type == SaleDocumentType.ticket
                && (taxId != null || legalName != null || fiscalAddress != null)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TICKET_FISCAL_DATA_NOT_ALLOWED",
                    "El ticket no admite datos fiscales de factura.");
        }
        return new NormalizedDocument(type, taxId, legalName, fiscalAddress);
    }

    private static NormalizedDeferredOrder normalizeDeferredOrder(
            CreateSaleRequest.DeferredOrder input) {
        if (input == null) return null;
        if (!isSupportedDeferredDeliveryMethod(input.deliveryMethod())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "DEFERRED_DELIVERY_METHOD_NOT_SUPPORTED",
                    "La venta diferida solo admite entrega a domicilio o retiro en tienda.");
        }
        String idempotencyKey = trimToNull(input.idempotencyKey());
        if (idempotencyKey == null || idempotencyKey.length() > 124) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "ORDER_IDEMPOTENCY_KEY_INVALID",
                    "La llave de idempotencia del pedido es invalida.");
        }
        if (input.transportMode() == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRANSPORT_MODE_REQUIRED",
                    "El modo de transporte es requerido.");
        }
        Map<String, Object> normalizedAddress = null;
        if (input.deliveryMethod() == DeliveryMethod.home_delivery) {
            CreateSaleRequest.DeliveryAddress address = input.deliveryAddress();
            if (address == null
                    || trimToNull(address.recipientName()) == null
                    || trimToNull(address.recipientPhone()) == null
                    || trimToNull(address.line1()) == null
                    || trimToNull(address.city()) == null
                    || trimToNull(address.country()) == null) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "DELIVERY_ADDRESS_REQUIRED",
                        "La entrega a domicilio requiere una direccion completa.");
            }
            String phone;
            try {
                phone = PhoneNormalizer.normalize(address.recipientPhone());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "DELIVERY_PHONE_INVALID",
                        "El telefono de entrega no es valido.");
            }
            normalizedAddress = new LinkedHashMap<>();
            normalizedAddress.put("recipientName", address.recipientName().trim());
            normalizedAddress.put("recipientPhone", phone);
            normalizedAddress.put("line1", address.line1().trim());
            putIfNotNull(normalizedAddress, "line2", trimToNull(address.line2()));
            normalizedAddress.put("city", address.city().trim());
            putIfNotNull(normalizedAddress, "stateOrDepartment", trimToNull(address.stateOrDepartment()));
            putIfNotNull(normalizedAddress, "postalCode", trimToNull(address.postalCode()));
            normalizedAddress.put("country", address.country().trim());
            putIfNotNull(normalizedAddress, "references", trimToNull(address.references()));
        }

        Map<String, Object> notification = null;
        if (input.notificationContact() != null) {
            String mode = trimToNull(input.notificationContact().emailMode());
            String email = trimToNull(input.notificationContact().email());
            if ("send".equals(mode)) {
                if (email == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "NOTIFICATION_EMAIL_REQUIRED",
                            "El correo de notificacion es requerido.");
                }
                notification = new LinkedHashMap<>();
                notification.put("emailMode", "send");
                notification.put("email", email.toLowerCase(java.util.Locale.ROOT));
            } else if ("not_applicable".equals(mode) && email == null) {
                notification = Map.of("emailMode", "not_applicable");
            } else {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "NOTIFICATION_CONTACT_INVALID",
                        "El contacto de notificacion es invalido.");
            }
        }
        return new NormalizedDeferredOrder(
                idempotencyKey,
                input.deliveryMethod(),
                input.transportMode(),
                normalizedAddress,
                notification);
    }

    private static boolean isSupportedDeferredDeliveryMethod(DeliveryMethod deliveryMethod) {
        return deliveryMethod == DeliveryMethod.home_delivery
                || deliveryMethod == DeliveryMethod.store_pickup;
    }

    private static void putIfNotNull(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fingerprint(CreateSaleRequest request, NormalizedDocument document) {
        String payload = request.branchId()
                + "|" + request.cashShiftId()
                + "|" + request.customerId()
                + "|" + document.type()
                + ":" + document.taxId()
                + ":" + document.legalName()
                + ":" + document.fiscalAddress()
                + "|" + request.sourceOrderId()
                + "|" + request.items().stream()
                        .map(item -> {
                            String base = item.productId() + ":"
                                    + item.quantity().stripTrailingZeros().toPlainString()
                                    + ":" + (item.discount() == null ? BigDecimal.ZERO : item.discount())
                                            .stripTrailingZeros().toPlainString();
                            return item.trackingSelections() == null || item.trackingSelections().isEmpty()
                                    ? base
                                    : base + ":" + selectionFingerprint(item.trackingSelections());
                        })
                        .sorted()
                        .collect(Collectors.joining(","))
                + "|" + request.payments().stream()
                        .map(payment -> payment.method() + ":"
                                + payment.amount().stripTrailingZeros().toPlainString()
                                + ":" + payment.bankAccountId()
                                + ":" + payment.reference()
                                + ":" + payment.externallyVerified())
                        .sorted()
                        .collect(Collectors.joining(","))
                + "|" + deferredFingerprint(normalizeDeferredOrder(request.deferredOrder()));
        return sha256(payload);
    }

    private static String deferredFingerprint(NormalizedDeferredOrder deferred) {
        if (deferred == null) return "immediate";
        return deferred.idempotencyKey()
                + ":" + deferred.deliveryMethod()
                + ":" + deferred.transportMode()
                + ":" + deferred.deliveryAddress()
                + ":" + deferred.notificationContact();
    }

    private static boolean matchesLegacyConfirmation(
            Sale sale, CreateSaleRequest request, NormalizedDocument document) {
        return sale.getDocumentType() == null
                && request.sourceOrderId() == null
                && request.deferredOrder() == null
                && document.type() == SaleDocumentType.ticket
                && java.util.Objects.equals(sale.getCashShiftId(), request.cashShiftId())
                && java.util.Objects.equals(sale.getCustomerId(), request.customerId())
                && legacyFingerprint(request).equals(sale.getConfirmationFingerprint());
    }

    private static String legacyFingerprint(CreateSaleRequest request) {
        String payload = request.branchId()
                + "|" + request.items().stream()
                        .map(item -> {
                            String base = item.productId() + ":"
                                    + item.quantity().stripTrailingZeros().toPlainString()
                                    + ":" + (item.discount() == null ? BigDecimal.ZERO : item.discount())
                                            .stripTrailingZeros().toPlainString();
                            return item.trackingSelections() == null || item.trackingSelections().isEmpty()
                                    ? base
                                    : base + ":" + selectionFingerprint(item.trackingSelections());
                        })
                        .sorted()
                        .collect(Collectors.joining(","))
                + "|" + request.payments().stream()
                        .map(payment -> payment.method() + ":"
                                + payment.amount().stripTrailingZeros().toPlainString()
                                + ":" + payment.bankAccountId()
                                + ":" + payment.reference()
                                + ":" + payment.externallyVerified())
                        .sorted()
                        .collect(Collectors.joining(","));
        return sha256(payload);
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    @SuppressWarnings("unused")
    private static String fingerprint(CreateSaleRequest request) {
        return fingerprint(request, normalizeDocument(request.document()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String selectionFingerprint(
            List<InventoryTrackingSelectionRequest> selections) {
        if (selections == null || selections.isEmpty()) return "-";
        return selections.stream()
                .map(selection -> selection == null
                        ? "<null-selection>"
                        : selection.productId()
                                + "/" + selection.locationId()
                                + "/" + selection.lotId()
                                + "/" + (selection.quantity() == null
                                        ? "null"
                                        : selection.quantity().stripTrailingZeros().toPlainString())
                                + "/" + safeSerials(selection.serialNumbers()).stream()
                                        .map(serial -> serial == null ? "<null>" : serial.trim())
                                        .sorted()
                                        .collect(Collectors.joining(";")))
                .sorted()
                .collect(Collectors.joining(","));
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
                "El historial de inventario de la venta es inconsistente.");
    }

    private static BusinessException incompleteDeferredConfirmation() {
        return BusinessException.conflict(
                "DEFERRED_CONFIRMATION_INCOMPLETE",
                "La venta diferida no conserva su Order y Picking relacionados.");
    }

    private record LinePricing(
            Product product,
            BigDecimal unitPrice,
            BigDecimal inventoryQuantity,
            BigDecimal discount,
            BigDecimal subtotal,
            UUID promotionId) {}

    private record NormalizedDocument(
            SaleDocumentType type,
            String taxId,
            String legalName,
            String fiscalAddress) {}

    private record NormalizedDeferredOrder(
            String idempotencyKey,
            DeliveryMethod deliveryMethod,
            com.omniretail.backend.ecommerce.entity.TransportMode transportMode,
            Map<String, Object> deliveryAddress,
            Map<String, Object> notificationContact) {}

    private record DeferredFulfillment(
            Order order, List<OrderItem> orderItems, PickingOrder picking) {}

    private record DeferredReservationPlan(
            Product product, OrderItem orderItem, BigDecimal quantity) {}

    private record SaleInventoryPlan(
            SaleItem saleItem,
            Product product,
            UUID locationId,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections,
            boolean kitComponent,
            boolean traceable) {}

    private record SaleRestorePlan(
            SaleItem saleItem,
            Product product,
            UUID locationId,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections,
            boolean kitComponent,
            boolean traceable) {}

    private record ExpectedInventory(
            Product product, BigDecimal quantity, boolean kitComponent) {}
}
