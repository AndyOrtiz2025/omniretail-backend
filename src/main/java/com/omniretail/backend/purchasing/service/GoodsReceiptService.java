package com.omniretail.backend.purchasing.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.inventory.dto.InventoryInboundCommand;
import com.omniretail.backend.inventory.dto.InventoryInboundTraceDetail;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.inventory.service.InventoryTraceabilityMutationService;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.purchasing.dto.CreateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptItemRequest;
import com.omniretail.backend.purchasing.dto.GoodsReceiptItemResponse;
import com.omniretail.backend.purchasing.dto.GoodsReceiptResponse;
import com.omniretail.backend.purchasing.dto.TrackingDetailRequest;
import com.omniretail.backend.purchasing.dto.UpdateGoodsReceiptRequest;
import com.omniretail.backend.purchasing.entity.GoodsReceipt;
import com.omniretail.backend.purchasing.entity.GoodsReceiptItem;
import com.omniretail.backend.purchasing.entity.GoodsReceiptStatus;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.purchasing.entity.PurchaseOrderStatus;
import com.omniretail.backend.purchasing.entity.ReceiptIncidentStatus;
import com.omniretail.backend.purchasing.repository.GoodsReceiptItemRepository;
import com.omniretail.backend.purchasing.repository.GoodsReceiptItemRepository.ConfirmedQuantity;
import com.omniretail.backend.purchasing.repository.GoodsReceiptRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderItemRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderRepository;
import com.omniretail.backend.purchasing.repository.ReceiptIncidentRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
@RequiredArgsConstructor
public class GoodsReceiptService {

    private static final TypeReference<List<TrackingDetailRequest>> TRACKING_DETAILS_TYPE =
            new TypeReference<>() {};

    private static final String RECEIPT_REASON = "Recepción de orden de compra";
    private static final String RECEIPT_REFERENCE_TYPE = "goods_receipt";
    private static final List<String> READ_PERMISSIONS = List.of(
            "receiving.receipts.read",
            "receiving.receipts.create",
            "receiving.receipts.confirm");
    private static final List<String> DRAFT_WRITE_PERMISSIONS = List.of(
            "receiving.receipts.create", "receiving.receipts.confirm");
    private static final Set<PurchaseOrderStatus> RECEIVABLE_STATUSES = Set.of(
            PurchaseOrderStatus.approved,
            PurchaseOrderStatus.sent,
            PurchaseOrderStatus.partially_received);

    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptItemRepository goodsReceiptItemRepository;
    private final ReceiptIncidentRepository receiptIncidentRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProductRepository productRepository;
    private final UnitRepository unitRepository;
    private final LocationRepository locationRepository;
    private final InventoryTraceabilityMutationService traceabilityMutationService;
    private final InventorySerialRepository inventorySerialRepository;
    private final DocumentCounterService documentCounterService;
    private final BranchAccessResolver branchAccessResolver;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final PermissionResolver permissionResolver;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public PageResponse<GoodsReceiptResponse> list(
            UUID branchId,
            UUID purchaseOrderId,
            GoodsReceiptStatus status,
            Pageable requestedPageable) {
        AuthenticatedUser actor = requireReadActor();
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (branchId != null) {
            requireBranchAccess(access, branchId);
        }

        Pageable pageable = safePageable(requestedPageable);
        Page<GoodsReceipt> page;
        if (access.allBranches()) {
            page = goodsReceiptRepository.findPage(
                    actor.tenantId(), branchId, purchaseOrderId, status, pageable);
        } else if (access.branchIds().isEmpty()) {
            page = Page.empty(pageable);
        } else {
            page = goodsReceiptRepository.findPageForBranches(
                    actor.tenantId(), access.branchIds(), branchId, purchaseOrderId, status, pageable);
        }

        ResponseContext context = responseContext(actor.tenantId(), page.getContent());
        return PageResponse.from(page, receipt -> response(receipt, context));
    }

    @Transactional(readOnly = true)
    public GoodsReceiptResponse get(UUID id) {
        AuthenticatedUser actor = requireReadActor();
        GoodsReceipt receipt = requireReceipt(actor.tenantId(), id);
        requireBranchAccess(branchAccessResolver.resolve(actor), receipt.getBranchId());
        return response(receipt, responseContext(actor.tenantId(), List.of(receipt)));
    }

    public GoodsReceiptResponse create(CreateGoodsReceiptRequest request) {
        AuthenticatedUser actor = requireDraftWriteActor();
        if (request == null || request.purchaseOrderId() == null) {
            throw badRequest("GOODS_RECEIPT_INVALID_REQUEST", "Los datos de la recepción son requeridos.");
        }
        UUID tenantId = actor.tenantId();
        PurchaseOrder order = requirePurchaseOrder(tenantId, request.purchaseOrderId());
        requireBranchAccess(branchAccessResolver.resolve(actor), order.getBranchId());
        requireReceivable(order);
        List<ResolvedItem> resolvedItems = resolveItems(tenantId, order, request.items());
        validateDraftLocations(tenantId, order.getBranchId(), resolvedItems);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .branchId(order.getBranchId())
                .purchaseOrderId(order.getId())
                .number(documentCounterService.nextGoodsReceiptNumber(tenantId))
                .status(GoodsReceiptStatus.draft)
                .notes(normalize(request.notes()))
                .build();
        receipt.setTenantId(tenantId);
        GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);
        List<GoodsReceiptItem> savedItems = saveResolvedItems(tenantId, savedReceipt.getId(), resolvedItems);
        return response(savedReceipt, order, savedItems, purchaseOrderItemsById(resolvedItems));
    }

    public GoodsReceiptResponse update(UUID id, UpdateGoodsReceiptRequest request) {
        AuthenticatedUser actor = requireDraftWriteActor();
        if (request == null) {
            throw badRequest("GOODS_RECEIPT_INVALID_REQUEST", "Los datos de la recepción son requeridos.");
        }
        UUID tenantId = actor.tenantId();
        GoodsReceipt receipt = requireReceiptForUpdate(tenantId, id);
        requireDraft(receipt);
        requireBranchAccess(branchAccessResolver.resolve(actor), receipt.getBranchId());
        PurchaseOrder order = requirePurchaseOrder(tenantId, receipt.getPurchaseOrderId());
        requireReceivable(order);

        // La colección completa se valida antes de modificar o eliminar una sola línea existente.
        List<ResolvedItem> resolvedItems = resolveItems(tenantId, order, request.items());
        validateDraftLocations(tenantId, order.getBranchId(), resolvedItems);
        receipt.setNotes(normalize(request.notes()));
        List<GoodsReceiptItem> savedItems = upsertItems(tenantId, receipt.getId(), resolvedItems);
        GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);
        return response(savedReceipt, order, savedItems, purchaseOrderItemsById(resolvedItems));
    }

    /**
     * UPSERT por purchaseOrderItemId: conserva el id de las líneas existentes (las incidencias las
     * referencian por FK), inserta las nuevas y elimina solo las omitidas sin incidencias. Corre bajo el
     * lock del receipt, que también toman la creación y resolución de incidencias.
     */
    private List<GoodsReceiptItem> upsertItems(
            UUID tenantId, UUID receiptId, List<ResolvedItem> resolvedItems) {
        Map<UUID, GoodsReceiptItem> existingByOrderItem = goodsReceiptItemRepository
                .findByTenantIdAndGoodsReceiptIdOrderByIdAsc(tenantId, receiptId)
                .stream()
                .collect(Collectors.toMap(
                        GoodsReceiptItem::getPurchaseOrderItemId, Function.identity()));
        Set<UUID> requestedOrderItemIds = resolvedItems.stream()
                .map(resolved -> resolved.purchaseOrderItem().getId())
                .collect(Collectors.toSet());

        // Fase 1: validar todo antes de persistir cualquier cambio.
        Set<UUID> lockedByIncident = new HashSet<>();
        for (GoodsReceiptItem existing : existingByOrderItem.values()) {
            if (receiptIncidentRepository.existsByTenantIdAndGoodsReceiptItemId(
                    tenantId, existing.getId())) {
                lockedByIncident.add(existing.getPurchaseOrderItemId());
            }
        }
        for (ResolvedItem resolved : resolvedItems) {
            UUID orderItemId = resolved.purchaseOrderItem().getId();
            GoodsReceiptItem existing = existingByOrderItem.get(orderItemId);
            if (existing != null
                    && lockedByIncident.contains(orderItemId)
                    && !sameReceivedValues(existing, resolved)) {
                throw BusinessException.conflict(
                        "GOODS_RECEIPT_ITEM_LOCKED_BY_INCIDENT",
                        "No se puede modificar cantidad, ubicación ni trazabilidad de una línea de recepción asociada a incidencias.");
            }
        }
        List<GoodsReceiptItem> omitted = existingByOrderItem.values().stream()
                .filter(existing -> !requestedOrderItemIds.contains(existing.getPurchaseOrderItemId()))
                .toList();
        if (omitted.stream().anyMatch(item -> lockedByIncident.contains(item.getPurchaseOrderItemId()))) {
            throw lineWithIncidentsCannotBeDeleted();
        }

        // Fase 2: eliminar omitidas; la FK RESTRICT sigue como última defensa ante una carrera.
        if (!omitted.isEmpty()) {
            try {
                goodsReceiptItemRepository.deleteAll(omitted);
                goodsReceiptItemRepository.flush();
            } catch (DataIntegrityViolationException exception) {
                if (isReceiptItemIncidentViolation(exception)) {
                    throw lineWithIncidentsCannotBeDeleted();
                }
                throw exception;
            }
        }

        // Fase 3: actualizar líneas existentes sin incidencias e insertar las nuevas.
        List<GoodsReceiptItem> toUpdate = new ArrayList<>();
        List<GoodsReceiptItem> toInsert = new ArrayList<>();
        for (ResolvedItem resolved : resolvedItems) {
            UUID orderItemId = resolved.purchaseOrderItem().getId();
            GoodsReceiptItem existing = existingByOrderItem.get(orderItemId);
            if (existing == null) {
                toInsert.add(newItem(tenantId, receiptId, resolved));
            } else if (!lockedByIncident.contains(orderItemId)) {
                applyResolved(existing, resolved);
                toUpdate.add(existing);
            }
        }
        goodsReceiptItemRepository.saveAll(toUpdate);
        Map<UUID, GoodsReceiptItem> inserted = goodsReceiptItemRepository.saveAllAndFlush(toInsert)
                .stream()
                .collect(Collectors.toMap(
                        GoodsReceiptItem::getPurchaseOrderItemId, Function.identity()));

        return resolvedItems.stream()
                .map(resolved -> {
                    UUID orderItemId = resolved.purchaseOrderItem().getId();
                    GoodsReceiptItem existing = existingByOrderItem.get(orderItemId);
                    return existing != null ? existing : inserted.get(orderItemId);
                })
                .toList();
    }

    private boolean sameReceivedValues(GoodsReceiptItem stored, ResolvedItem resolved) {
        return stored.getReceivedQuantity().compareTo(resolved.receivedQuantity()) == 0
                && Objects.equals(stored.getLocationId(), resolved.locationId())
                && sameTrackingDetails(
                        deserializeTrackingDetails(stored.getTrackingDetails()),
                        outboundTrackingDetails(resolved.trackingDetails()));
    }

    private static boolean sameTrackingDetails(
            List<TrackingDetailRequest> stored, List<TrackingDetailRequest> requested) {
        if (stored.size() != requested.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            TrackingDetailRequest left = stored.get(i);
            TrackingDetailRequest right = requested.get(i);
            boolean sameQuantity = left.baseQuantity() == null || right.baseQuantity() == null
                    ? left.baseQuantity() == right.baseQuantity()
                    : left.baseQuantity().compareTo(right.baseQuantity()) == 0;
            if (!sameQuantity
                    || !Objects.equals(left.lotNumber(), right.lotNumber())
                    || !Objects.equals(left.expirationDate(), right.expirationDate())
                    || !Objects.equals(
                            left.serialNumbers() == null ? List.of() : left.serialNumbers(),
                            right.serialNumbers() == null ? List.of() : right.serialNumbers())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReceiptItemIncidentViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("fk_receipt_incidents_receipt_item")) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException lineWithIncidentsCannotBeDeleted() {
        return BusinessException.conflict(
                "GOODS_RECEIPT_ITEM_HAS_INCIDENTS",
                "No se puede eliminar una línea de recepción asociada a incidencias.");
    }

    public void delete(UUID id) {
        AuthenticatedUser actor = requireDraftWriteActor();
        GoodsReceipt receipt = requireReceiptForUpdate(actor.tenantId(), id);
        requireDraft(receipt);
        requireBranchAccess(branchAccessResolver.resolve(actor), receipt.getBranchId());
        goodsReceiptItemRepository.deleteByTenantIdAndGoodsReceiptId(actor.tenantId(), receipt.getId());
        goodsReceiptRepository.deleteByTenantIdAndId(actor.tenantId(), receipt.getId());
        goodsReceiptRepository.flush();
    }

    public GoodsReceiptResponse confirm(UUID id) {
        AuthenticatedUser actor = requireConfirmActor();
        UUID tenantId = actor.tenantId();

        // Orden global de locks: Receipt -> PurchaseOrder -> InventoryBalance.
        GoodsReceipt receipt = requireReceiptForUpdate(tenantId, id);
        requireDraft(receipt);
        PurchaseOrder order = requirePurchaseOrderForUpdate(tenantId, receipt.getPurchaseOrderId());
        requireBranchAccess(branchAccessResolver.resolve(actor), order.getBranchId());
        requireReceivable(order);
        if (receiptIncidentRepository.existsByTenantIdAndGoodsReceiptIdAndStatus(
                tenantId, receipt.getId(), ReceiptIncidentStatus.open)) {
            throw BusinessException.conflict(
                    "RECEIPT_HAS_OPEN_INCIDENTS",
                    "La recepcion tiene incidencias abiertas que deben resolverse antes de confirmar.");
        }

        List<GoodsReceiptItem> storedItems = goodsReceiptItemRepository
                .findByTenantIdAndGoodsReceiptIdOrderByIdAsc(tenantId, receipt.getId());
        if (storedItems.isEmpty()) {
            throw badRequest("GOODS_RECEIPT_ITEMS_REQUIRED", "La recepción debe contener al menos un producto.");
        }
        List<GoodsReceiptItemRequest> requests = storedItems.stream()
                .map(item -> new GoodsReceiptItemRequest(
                        item.getPurchaseOrderItemId(),
                        item.getReceivedQuantity(),
                        item.getLocationId(),
                        deserializeTrackingDetails(item.getTrackingDetails())))
                .toList();
        List<ResolvedItem> resolvedItems = resolveItems(tenantId, order, requests);
        validateDraftLocations(tenantId, order.getBranchId(), resolvedItems);
        refreshStoredItems(storedItems, resolvedItems);

        Map<UUID, BigDecimal> confirmedBefore = confirmedQuantities(tenantId, order.getId());
        validateNoOverReceiving(resolvedItems, confirmedBefore);

        Map<UUID, GoodsReceiptItem> storedByOrderItem = storedItems.stream().collect(
                Collectors.toMap(GoodsReceiptItem::getPurchaseOrderItemId, Function.identity()));
        List<ResolvedItem> inventoryOrder = resolvedItems.stream()
                .sorted(Comparator.comparing(
                                (ResolvedItem item) -> item.purchaseOrderItem().getProductId())
                        .thenComparing(ResolvedItem::locationId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(item -> item.purchaseOrderItem().getId()))
                .toList();
        for (ResolvedItem resolved : inventoryOrder) {
            if (Boolean.TRUE.equals(resolved.product().getTrackingStock())) {
                GoodsReceiptItem stored = storedByOrderItem.get(
                        resolved.purchaseOrderItem().getId());
                traceabilityMutationService.receive(new InventoryInboundCommand(
                        tenantId,
                        order.getBranchId(),
                        resolved.product(),
                        resolved.locationId(),
                        resolved.baseQuantity(),
                        resolved.trackingDetails(),
                        RECEIPT_REASON,
                        RECEIPT_REFERENCE_TYPE,
                        receipt.getId(),
                        stored.getId(),
                        actor.userId()));
            }
        }

        receipt.setStatus(GoodsReceiptStatus.confirmed);
        receipt.setReceivedAt(Instant.now());
        receipt.setReceivedByUserId(actor.userId());
        recalculatePurchaseOrderStatus(order, resolvedItems, confirmedBefore, tenantId);
        purchaseOrderRepository.save(order);
        GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);
        return response(savedReceipt, order, storedItems, purchaseOrderItemsById(resolvedItems));
    }

    /**
     * Agrega mercancía de reposición a una línea protegida por incidencia. Es la única vía que muta
     * cantidad/tracking de una línea con incidencias (el PUT normal sigue bloqueado). El llamador ya
     * tiene el lock del receipt; aquí se toma el de la orden (orden global Receipt -> PurchaseOrder).
     * No toca stock ni movimientos: eso ocurre solo en confirm().
     */
    public void appendIncidentReplacement(
            UUID tenantId,
            GoodsReceipt receipt,
            GoodsReceiptItem item,
            BigDecimal expectedQuantity,
            BigDecimal replacementQuantity,
            List<TrackingDetailRequest> replacementDetails) {
        requireDraft(receipt);
        PurchaseOrder order = requirePurchaseOrderForUpdate(tenantId, receipt.getPurchaseOrderId());
        requireReceivable(order);
        PurchaseOrderItem orderItem = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, order.getId())
                .stream()
                .filter(candidate -> candidate.getId().equals(item.getPurchaseOrderItemId()))
                .findFirst()
                .orElseThrow(GoodsReceiptService::purchaseOrderItemNotFound);

        if (replacementQuantity == null || expectedQuantity == null
                || replacementQuantity.compareTo(expectedQuantity) != 0) {
            throw badRequest(
                    "RECEIPT_REPLACEMENT_QUANTITY_MISMATCH",
                    "La cantidad de reposición debe ser exactamente la cantidad afectada de la incidencia.");
        }
        // Cabe si ordenado - confirmado - aceptado - OTRAS incidencias abiertas >= reposición. La propia
        // incidencia sigue abierta aquí, por eso se suma de vuelta a pendingQuantity.
        BigDecimal remaining = pendingQuantity(tenantId, receipt, item, orderItem).add(expectedQuantity);
        if (remaining.signum() <= 0) {
            throw BusinessException.conflict(
                    "RECEIPT_REPLACEMENT_NOT_ALLOWED",
                    "La línea no tiene cantidad pendiente por recibir; resuelve la incidencia sin reposición.");
        }
        if (replacementQuantity.compareTo(remaining) > 0) {
            throw BusinessException.conflict(
                    "RECEIPT_REPLACEMENT_EXCEEDS_REMAINING",
                    "La reposición supera la cantidad pendiente por recibir de la orden de compra.");
        }

        Product product = requireProduct(tenantId, item.getProductId());
        Unit purchaseUnit = requireUnit(tenantId, item.getUnitId());
        Unit baseUnit = requireUnit(tenantId, product.getBaseUnitId());
        validateReceivedQuantity(replacementQuantity, purchaseUnit);
        BigDecimal newTotal = item.getReceivedQuantity().add(replacementQuantity);
        BigDecimal replacementBase = calculateBaseQuantity(replacementQuantity, item.getPurchaseToBaseFactor());
        BigDecimal newTotalBase = calculateBaseQuantity(newTotal, item.getPurchaseToBaseFactor());
        requireBaseQuantityShape(product, baseUnit, replacementBase);
        requireBaseQuantityShape(product, baseUnit, newTotalBase);

        List<InventoryInboundTraceDetail> newDetails = traceabilityMutationService.validateAndNormalize(
                tenantId, product, replacementBase, inboundTrackingDetails(replacementDetails));
        List<InventoryInboundTraceDetail> finalDetails = List.of();
        if (!newDetails.isEmpty()) {
            requireNewSerialsAvailable(tenantId, receipt.getId(), item, product, newDetails);
            List<InventoryInboundTraceDetail> merged = mergeTrackingDetails(
                    inboundTrackingDetails(deserializeTrackingDetails(item.getTrackingDetails())),
                    newDetails);
            // Revalida la línea completa: lotes únicos, seriales únicos y suma == nueva cantidad base.
            finalDetails = traceabilityMutationService.validateAndNormalize(
                    tenantId, product, newTotalBase, merged);
        }

        item.setReceivedQuantity(newTotal);
        item.setBaseQuantity(newTotalBase);
        item.setTrackingDetails(serializeTrackingDetails(finalDetails));
        goodsReceiptItemRepository.saveAndFlush(item);
    }

    /**
     * Cantidad de la línea de orden aún sin justificar:
     * ordenado - confirmado previo - aceptado (receivedQuantity del draft) - incidencias OPEN de la línea.
     * Una incidencia es mercancía afectada y NO aceptada: no forma parte de receivedQuantity. Las
     * resueltas ya se sumaron a receivedQuantity, por eso no cuentan aquí. Puede ser negativa solo
     * con datos previos inconsistentes.
     */
    public BigDecimal pendingQuantity(UUID tenantId, GoodsReceipt receipt, GoodsReceiptItem item) {
        PurchaseOrderItem orderItem = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, receipt.getPurchaseOrderId())
                .stream()
                .filter(candidate -> candidate.getId().equals(item.getPurchaseOrderItemId()))
                .findFirst()
                .orElseThrow(GoodsReceiptService::purchaseOrderItemNotFound);
        return pendingQuantity(tenantId, receipt, item, orderItem);
    }

    private BigDecimal pendingQuantity(
            UUID tenantId, GoodsReceipt receipt, GoodsReceiptItem item, PurchaseOrderItem orderItem) {
        // Si el receipt ya está confirmado, su línea ya está dentro de la suma confirmada.
        BigDecimal confirmed = confirmedQuantities(tenantId, receipt.getPurchaseOrderId())
                .getOrDefault(orderItem.getId(), BigDecimal.ZERO);
        BigDecimal accepted = receipt.getStatus() == GoodsReceiptStatus.draft
                ? item.getReceivedQuantity()
                : BigDecimal.ZERO;
        BigDecimal openIncidents = receiptIncidentRepository.sumQuantityAffectedByItemAndStatus(
                tenantId, item.getId(), ReceiptIncidentStatus.open);
        return orderItem.getQuantity().subtract(confirmed).subtract(accepted).subtract(openIncidents);
    }

    private void requireBaseQuantityShape(Product product, Unit baseUnit, BigDecimal baseQuantity) {
        if ((!baseUnit.getAllowsDecimals() || Boolean.TRUE.equals(product.getTrackingSerial()))
                && !isInteger(baseQuantity)) {
            throw badRequest(
                    "GOODS_RECEIPT_INVALID_BASE_QUANTITY",
                    "La cantidad convertida a la unidad base debe ser entera.");
        }
    }

    private void requireNewSerialsAvailable(
            UUID tenantId,
            UUID receiptId,
            GoodsReceiptItem item,
            Product product,
            List<InventoryInboundTraceDetail> newDetails) {
        List<String> newSerials = newDetails.stream()
                .flatMap(detail -> detail.serialNumbers().stream())
                .toList();
        if (newSerials.isEmpty()) {
            return;
        }
        // Una sola consulta batch; la unicidad al confirmar sigue siendo la garantía definitiva.
        if (!inventorySerialRepository
                .findExistingSerialNumbers(tenantId, product.getId(), newSerials)
                .isEmpty()) {
            throw duplicateSerialConflict();
        }
        Set<String> requested = new HashSet<>(newSerials);
        for (GoodsReceiptItem other : goodsReceiptItemRepository
                .findByTenantIdAndGoodsReceiptIdOrderByIdAsc(tenantId, receiptId)) {
            if (other.getId().equals(item.getId())) {
                continue;
            }
            for (TrackingDetailRequest detail : deserializeTrackingDetails(other.getTrackingDetails())) {
                if (detail.serialNumbers() != null
                        && detail.serialNumbers().stream().anyMatch(requested::contains)) {
                    throw duplicateSerialConflict();
                }
            }
        }
    }

    /** Agrega los detalles nuevos a los existentes; un lote ya presente acumula, nunca se sobrescribe. */
    private static List<InventoryInboundTraceDetail> mergeTrackingDetails(
            List<InventoryInboundTraceDetail> existing, List<InventoryInboundTraceDetail> added) {
        List<InventoryInboundTraceDetail> merged = new ArrayList<>(existing);
        for (InventoryInboundTraceDetail detail : added) {
            int index = -1;
            for (int i = 0; i < merged.size() && detail.lotNumber() != null; i++) {
                if (detail.lotNumber().equals(merged.get(i).lotNumber())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                merged.add(detail);
                continue;
            }
            InventoryInboundTraceDetail current = merged.get(index);
            if (!Objects.equals(current.expirationDate(), detail.expirationDate())) {
                throw BusinessException.conflict(
                        "LOT_EXPIRATION_MISMATCH",
                        "El lote ya existe en la línea con otra fecha de vencimiento.");
            }
            List<String> serials = new ArrayList<>(current.serialNumbers());
            serials.addAll(detail.serialNumbers());
            merged.set(index, new InventoryInboundTraceDetail(
                    current.baseQuantity().add(detail.baseQuantity()),
                    current.lotNumber(),
                    current.expirationDate(),
                    serials));
        }
        return merged;
    }

    private static BusinessException duplicateSerialConflict() {
        return BusinessException.conflict(
                "DUPLICATE_SERIAL", "Uno o mas numeros de serie ya existen o estan repetidos.");
    }

    private List<ResolvedItem> resolveItems(
            UUID tenantId, PurchaseOrder order, List<GoodsReceiptItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw badRequest("GOODS_RECEIPT_ITEMS_REQUIRED", "La recepción debe contener al menos un producto.");
        }
        Map<UUID, PurchaseOrderItem> orderItems = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, order.getId())
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        List<ResolvedItem> resolved = new ArrayList<>(requests.size());
        for (GoodsReceiptItemRequest request : requests) {
            if (request == null || request.purchaseOrderItemId() == null) {
                throw purchaseOrderItemNotFound();
            }
            if (!seen.add(request.purchaseOrderItemId())) {
                throw badRequest(
                        "GOODS_RECEIPT_DUPLICATE_PO_ITEM",
                        "Una línea de la orden no puede repetirse dentro de la misma recepción.");
            }
            PurchaseOrderItem orderItem = orderItems.get(request.purchaseOrderItemId());
            if (orderItem == null) {
                throw purchaseOrderItemNotFound();
            }
            Product product = requireProduct(tenantId, orderItem.getProductId());
            if (product.getProductType() == ProductType.kit) {
                throw badRequest("GOODS_RECEIPT_KIT_NOT_ALLOWED", "Los kits no se reciben como inventario propio.");
            }
            Unit purchaseUnit = requireUnit(tenantId, orderItem.getUnitId());
            Unit baseUnit = requireUnit(tenantId, product.getBaseUnitId());
            validateReceivedQuantity(request.receivedQuantity(), purchaseUnit);
            BigDecimal baseQuantity = calculateBaseQuantity(
                    request.receivedQuantity(), orderItem.getPurchaseToBaseFactor());
            if ((!baseUnit.getAllowsDecimals() || Boolean.TRUE.equals(product.getTrackingSerial()))
                    && !isInteger(baseQuantity)) {
                throw badRequest(
                        "GOODS_RECEIPT_INVALID_BASE_QUANTITY",
                        "La cantidad convertida a la unidad base debe ser entera.");
            }
            UUID locationId = null;
            if (Boolean.TRUE.equals(product.getTrackingStock())) {
                if (request.locationId() == null) {
                    throw badRequest(
                            "GOODS_RECEIPT_LOCATION_REQUIRED",
                            "La ubicación es obligatoria para productos con control de inventario.");
                }
                locationId = request.locationId();
            }
            List<InventoryInboundTraceDetail> trackingDetails = traceabilityMutationService
                    .validateAndNormalize(
                            tenantId,
                            product,
                            baseQuantity,
                            inboundTrackingDetails(request.trackingDetails()));
            resolved.add(new ResolvedItem(
                    orderItem,
                    product,
                    request.receivedQuantity(),
                    baseQuantity,
                    locationId,
                    trackingDetails));
        }
        validateSerialUniquenessAcrossItems(resolved);
        return resolved;
    }

    private List<GoodsReceiptItem> saveResolvedItems(
            UUID tenantId, UUID receiptId, List<ResolvedItem> resolvedItems) {
        List<GoodsReceiptItem> items = resolvedItems.stream()
                .map(resolved -> newItem(tenantId, receiptId, resolved))
                .toList();
        return goodsReceiptItemRepository.saveAllAndFlush(items);
    }

    private GoodsReceiptItem newItem(UUID tenantId, UUID receiptId, ResolvedItem resolved) {
        return GoodsReceiptItem.builder()
                .tenantId(tenantId)
                .goodsReceiptId(receiptId)
                .purchaseOrderItemId(resolved.purchaseOrderItem().getId())
                .productId(resolved.purchaseOrderItem().getProductId())
                .locationId(resolved.locationId())
                .receivedQuantity(resolved.receivedQuantity())
                .unitId(resolved.purchaseOrderItem().getUnitId())
                .unitSymbolSnapshot(resolved.purchaseOrderItem().getUnitSymbolSnapshot())
                .purchaseToBaseFactor(resolved.purchaseOrderItem().getPurchaseToBaseFactor())
                .baseQuantity(resolved.baseQuantity())
                .unitCost(resolved.purchaseOrderItem().getUnitCost())
                .trackingDetails(serializeTrackingDetails(resolved.trackingDetails()))
                .build();
    }

    private void validateDraftLocations(
            UUID tenantId, UUID branchId, List<ResolvedItem> resolvedItems) {
        for (ResolvedItem resolved : resolvedItems) {
            if (!Boolean.TRUE.equals(resolved.product().getTrackingStock())) {
                continue;
            }
            Location location = locationRepository
                    .findByTenantIdAndId(tenantId, resolved.locationId())
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND,
                            "LOCATION_NOT_FOUND",
                            "Ubicación no encontrada."));
            if (!location.getBranchId().equals(branchId)) {
                throw badRequest(
                        "LOCATION_BRANCH_MISMATCH",
                        "La ubicación no pertenece a la sucursal indicada.");
            }
            if (location.getStatus() != LocationStatus.active) {
                throw badRequest(
                        "LOCATION_NOT_ACTIVE",
                        "La ubicación debe estar activa para recibir inventario.");
            }
        }
    }

    private void refreshStoredItems(
            List<GoodsReceiptItem> storedItems, List<ResolvedItem> resolvedItems) {
        Map<UUID, ResolvedItem> resolvedByOrderItem = resolvedItems.stream().collect(
                Collectors.toMap(item -> item.purchaseOrderItem().getId(), Function.identity()));
        for (GoodsReceiptItem stored : storedItems) {
            applyResolved(stored, resolvedByOrderItem.get(stored.getPurchaseOrderItemId()));
        }
        goodsReceiptItemRepository.saveAll(storedItems);
    }

    private void applyResolved(GoodsReceiptItem stored, ResolvedItem resolved) {
        PurchaseOrderItem orderItem = resolved.purchaseOrderItem();
        stored.setProductId(orderItem.getProductId());
        stored.setLocationId(resolved.locationId());
        stored.setReceivedQuantity(resolved.receivedQuantity());
        stored.setUnitId(orderItem.getUnitId());
        stored.setUnitSymbolSnapshot(orderItem.getUnitSymbolSnapshot());
        stored.setPurchaseToBaseFactor(orderItem.getPurchaseToBaseFactor());
        stored.setBaseQuantity(resolved.baseQuantity());
        stored.setUnitCost(orderItem.getUnitCost());
        stored.setTrackingDetails(serializeTrackingDetails(resolved.trackingDetails()));
    }

    private void validateNoOverReceiving(
            List<ResolvedItem> currentItems, Map<UUID, BigDecimal> confirmedBefore) {
        for (ResolvedItem current : currentItems) {
            UUID itemId = current.purchaseOrderItem().getId();
            BigDecimal total = confirmedBefore.getOrDefault(itemId, BigDecimal.ZERO)
                    .add(current.receivedQuantity());
            if (total.compareTo(current.purchaseOrderItem().getQuantity()) > 0) {
                throw BusinessException.conflict(
                        "GOODS_RECEIPT_OVER_RECEIVING",
                        "La cantidad recibida excede la cantidad ordenada para una línea de compra.");
            }
        }
    }

    private void recalculatePurchaseOrderStatus(
            PurchaseOrder order,
            List<ResolvedItem> currentItems,
            Map<UUID, BigDecimal> confirmedBefore,
            UUID tenantId) {
        Map<UUID, BigDecimal> totals = new HashMap<>(confirmedBefore);
        currentItems.forEach(item -> totals.merge(
                item.purchaseOrderItem().getId(), item.receivedQuantity(), BigDecimal::add));
        List<PurchaseOrderItem> allOrderItems = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrderIdOrderByIdAsc(tenantId, order.getId());
        boolean allReceived = !allOrderItems.isEmpty() && allOrderItems.stream().allMatch(item ->
                totals.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(item.getQuantity()) == 0);
        order.setStatus(allReceived ? PurchaseOrderStatus.received : PurchaseOrderStatus.partially_received);
    }

    private Map<UUID, BigDecimal> confirmedQuantities(UUID tenantId, UUID purchaseOrderId) {
        return goodsReceiptItemRepository
                .sumConfirmedByPurchaseOrderItem(tenantId, purchaseOrderId, GoodsReceiptStatus.confirmed)
                .stream()
                .collect(Collectors.toMap(
                        ConfirmedQuantity::getPurchaseOrderItemId,
                        ConfirmedQuantity::getReceivedQuantity));
    }

    private ResponseContext responseContext(UUID tenantId, List<GoodsReceipt> receipts) {
        if (receipts.isEmpty()) {
            return ResponseContext.EMPTY;
        }
        List<UUID> receiptIds = receipts.stream().map(GoodsReceipt::getId).toList();
        Map<UUID, List<GoodsReceiptItem>> itemsByReceipt = goodsReceiptItemRepository
                .findForReceipts(tenantId, receiptIds)
                .stream()
                .collect(Collectors.groupingBy(
                        GoodsReceiptItem::getGoodsReceiptId, HashMap::new, Collectors.toList()));
        Set<UUID> orderIds = receipts.stream().map(GoodsReceipt::getPurchaseOrderId).collect(Collectors.toSet());
        Map<UUID, PurchaseOrder> ordersById = purchaseOrderRepository
                .findByTenantIdAndIdIn(tenantId, orderIds)
                .stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        Map<UUID, PurchaseOrderItem> orderItemsById = purchaseOrderItemRepository
                .findForOrders(tenantId, orderIds)
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        return new ResponseContext(itemsByReceipt, ordersById, orderItemsById);
    }

    private GoodsReceiptResponse response(GoodsReceipt receipt, ResponseContext context) {
        PurchaseOrder order = context.ordersById().get(receipt.getPurchaseOrderId());
        return response(
                receipt,
                order,
                context.itemsByReceipt().getOrDefault(receipt.getId(), List.of()),
                context.orderItemsById());
    }

    private GoodsReceiptResponse response(
            GoodsReceipt receipt,
            PurchaseOrder order,
            List<GoodsReceiptItem> items,
            Map<UUID, PurchaseOrderItem> orderItemsById) {
        List<GoodsReceiptItemResponse> itemResponses = items.stream()
                .map(item -> {
                    PurchaseOrderItem orderItem = orderItemsById.get(item.getPurchaseOrderItemId());
                    return new GoodsReceiptItemResponse(
                            item.getId(),
                            item.getPurchaseOrderItemId(),
                            item.getProductId(),
                            orderItem != null ? orderItem.getProductNameSnapshot() : null,
                            orderItem != null ? orderItem.getProductSkuSnapshot() : null,
                            item.getReceivedQuantity(),
                            item.getUnitId(),
                            item.getUnitSymbolSnapshot(),
                            item.getPurchaseToBaseFactor(),
                            item.getBaseQuantity(),
                            item.getLocationId(),
                            item.getUnitCost(),
                            deserializeTrackingDetails(item.getTrackingDetails()));
                })
                .toList();
        return new GoodsReceiptResponse(
                receipt.getId(),
                receipt.getBranchId(),
                receipt.getPurchaseOrderId(),
                order != null ? order.getNumber() : null,
                receipt.getNumber(),
                receipt.getStatus(),
                receipt.getReceivedAt(),
                receipt.getNotes(),
                receipt.getReceivedByUserId(),
                receipt.getCreatedAt(),
                receipt.getUpdatedAt(),
                itemResponses);
    }

    private static Map<UUID, PurchaseOrderItem> purchaseOrderItemsById(List<ResolvedItem> items) {
        return items.stream().map(ResolvedItem::purchaseOrderItem).collect(
                Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
    }

    private static List<InventoryInboundTraceDetail> inboundTrackingDetails(
            List<TrackingDetailRequest> details) {
        if (details == null) return List.of();
        return details.stream()
                .map(detail -> detail == null
                        ? null
                        : new InventoryInboundTraceDetail(
                                detail.baseQuantity(),
                                detail.lotNumber(),
                                detail.expirationDate(),
                                detail.serialNumbers()))
                .toList();
    }

    private static List<TrackingDetailRequest> outboundTrackingDetails(
            List<InventoryInboundTraceDetail> details) {
        return details.stream()
                .map(detail -> new TrackingDetailRequest(
                        detail.baseQuantity(),
                        detail.lotNumber(),
                        detail.expirationDate(),
                        detail.serialNumbers()))
                .toList();
    }

    private String serializeTrackingDetails(List<InventoryInboundTraceDetail> details) {
        return jsonMapper.writeValueAsString(outboundTrackingDetails(details));
    }

    private List<TrackingDetailRequest> deserializeTrackingDetails(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<TrackingDetailRequest> details = jsonMapper.readValue(value, TRACKING_DETAILS_TYPE);
            return details == null ? List.of() : details;
        } catch (RuntimeException exception) {
            throw badRequest(
                    "GOODS_RECEIPT_TRACKING_DETAILS_INVALID",
                    "La metadata trazable persistida no es valida.");
        }
    }

    private static void validateSerialUniquenessAcrossItems(List<ResolvedItem> items) {
        Set<String> serials = new HashSet<>();
        for (ResolvedItem item : items) {
            for (InventoryInboundTraceDetail detail : item.trackingDetails()) {
                for (String serial : detail.serialNumbers()) {
                    if (!serials.add(serial)) {
                        throw BusinessException.conflict(
                                "DUPLICATE_SERIAL",
                                "Un numero de serie no puede repetirse entre lineas de la recepcion.");
                    }
                }
            }
        }
    }

    private AuthenticatedUser requireReadActor() {
        AuthenticatedUser actor = currentUser.require();
        requireAnyPermission(actor, READ_PERMISSIONS);
        return actor;
    }

    private AuthenticatedUser requireDraftWriteActor() {
        AuthenticatedUser actor = currentUser.require();
        requireAnyPermission(actor, DRAFT_WRITE_PERMISSIONS);
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.receiving);
        return actor;
    }

    private AuthenticatedUser requireConfirmActor() {
        AuthenticatedUser actor = currentUser.require();
        requireAnyPermission(actor, List.of("receiving.receipts.confirm"));
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.receiving);
        return actor;
    }

    private void requireAnyPermission(AuthenticatedUser actor, Collection<String> permissions) {
        if (actor.roleId() == null
                || permissions.stream().noneMatch(permission -> permissionResolver.hasPermission(
                        actor.tenantId(), actor.roleId(), permission))) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED", "No tienes permiso para realizar esta operación de recepción.");
        }
    }

    private GoodsReceipt requireReceipt(UUID tenantId, UUID id) {
        return goodsReceiptRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(GoodsReceiptService::receiptNotFound);
    }

    private GoodsReceipt requireReceiptForUpdate(UUID tenantId, UUID id) {
        return goodsReceiptRepository
                .findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(GoodsReceiptService::receiptNotFound);
    }

    private PurchaseOrder requirePurchaseOrder(UUID tenantId, UUID id) {
        return purchaseOrderRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(GoodsReceiptService::purchaseOrderNotFound);
    }

    private PurchaseOrder requirePurchaseOrderForUpdate(UUID tenantId, UUID id) {
        return purchaseOrderRepository
                .findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(GoodsReceiptService::purchaseOrderNotFound);
    }

    private Product requireProduct(UUID tenantId, UUID id) {
        return productRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "GOODS_RECEIPT_PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private Unit requireUnit(UUID tenantId, UUID id) {
        return unitRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "GOODS_RECEIPT_UNIT_NOT_FOUND", "Unidad no encontrada."));
    }

    private static void requireBranchAccess(BranchAccess access, UUID branchId) {
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }

    private static void requireReceivable(PurchaseOrder order) {
        if (!RECEIVABLE_STATUSES.contains(order.getStatus())) {
            throw BusinessException.conflict(
                    "GOODS_RECEIPT_PO_NOT_RECEIVABLE",
                    "La orden de compra no permite recepciones en su estado actual.");
        }
    }

    private static void requireDraft(GoodsReceipt receipt) {
        if (receipt.getStatus() != GoodsReceiptStatus.draft) {
            throw BusinessException.conflict(
                    "GOODS_RECEIPT_INVALID_STATUS",
                    "La recepción ya fue confirmada y es inmutable.");
        }
    }

    private static void validateReceivedQuantity(BigDecimal quantity, Unit purchaseUnit) {
        if (!fitsDecimal(quantity, 12, 3) || quantity.signum() <= 0) {
            throw badRequest("GOODS_RECEIPT_INVALID_QUANTITY", "La cantidad recibida no es válida.");
        }
        if (!purchaseUnit.getAllowsDecimals() && !isInteger(quantity)) {
            throw badRequest(
                    "GOODS_RECEIPT_INVALID_QUANTITY",
                    "La unidad de compra solo admite cantidades enteras.");
        }
    }

    private static BigDecimal calculateBaseQuantity(BigDecimal receivedQuantity, BigDecimal factor) {
        if (!fitsDecimal(factor, 18, 6) || factor.signum() <= 0) {
            throw badRequest("GOODS_RECEIPT_INVALID_FACTOR", "El factor de compra no es válido.");
        }
        BigDecimal result = receivedQuantity.multiply(factor);
        if (!fitsDecimal(result, 12, 3) || result.signum() <= 0) {
            throw badRequest(
                    "GOODS_RECEIPT_INVALID_BASE_QUANTITY",
                    "La cantidad convertida excede el valor permitido.");
        }
        try {
            return result.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw badRequest(
                    "GOODS_RECEIPT_INVALID_BASE_QUANTITY",
                    "La cantidad convertida excede tres decimales.");
        }
    }

    private static boolean fitsDecimal(BigDecimal value, int precision, int scale) {
        if (value == null) {
            return false;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionDigits <= scale && integerDigits <= precision - scale;
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static Pageable safePageable(Pageable requested) {
        int size = Math.min(Math.max(requested.getPageSize(), 1), 100);
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static BusinessException receiptNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "GOODS_RECEIPT_NOT_FOUND", "Recepción de compra no encontrada.");
    }

    private static BusinessException purchaseOrderNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "PURCHASE_ORDER_NOT_FOUND", "Orden de compra no encontrada.");
    }

    private static BusinessException purchaseOrderItemNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "GOODS_RECEIPT_PO_ITEM_NOT_FOUND",
                "Línea de orden de compra no encontrada.");
    }

    private static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record ResolvedItem(
            PurchaseOrderItem purchaseOrderItem,
            Product product,
            BigDecimal receivedQuantity,
            BigDecimal baseQuantity,
            UUID locationId,
            List<InventoryInboundTraceDetail> trackingDetails) {}

    private record ResponseContext(
            Map<UUID, List<GoodsReceiptItem>> itemsByReceipt,
            Map<UUID, PurchaseOrder> ordersById,
            Map<UUID, PurchaseOrderItem> orderItemsById) {
        private static final ResponseContext EMPTY = new ResponseContext(Map.of(), Map.of(), Map.of());
    }
}
