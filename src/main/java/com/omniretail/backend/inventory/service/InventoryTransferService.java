package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferItemResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferReceiptItemResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferReceiptResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestEffectiveStatus;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.InventoryHistoricalTraceDetail;
import com.omniretail.backend.inventory.dto.InventoryPhysicalSelection;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.dto.InventoryTransferTrackingSelectionRequest;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferItemRequest;
import com.omniretail.backend.inventory.dto.ReceiveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import com.omniretail.backend.inventory.entity.InventoryTransferReceipt;
import com.omniretail.backend.inventory.entity.InventoryTransferReceiptItem;
import com.omniretail.backend.inventory.entity.InventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferReceiptItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferReceiptRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.logistics.service.PickingService;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryTransferService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final InventoryTransferRequestRepository requestRepository;
    private final InventoryTransferRepository transferRepository;
    private final InventoryTransferItemRepository itemRepository;
    private final InventoryTransferReceiptRepository receiptRepository;
    private final InventoryTransferReceiptItemRepository receiptItemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationLifecycleService reservationLifecycleService;
    private final InventoryStockService inventoryStockService;
    private final InventoryOperationalLocationService operationalLocationService;
    private final InventoryTraceabilityMutationService traceabilityMutationService;
    private final InventoryTraceabilityHistoryService traceabilityHistoryService;
    private final InventoryPhysicalSelectionCodec physicalSelectionCodec;
    private final InventoryMovementRepository movementRepository;
    private final DocumentCounterService documentCounterService;
    private final PickingService pickingService;
    private final PickingOrderRepository pickingOrderRepository;
    private final PickingItemRepository pickingItemRepository;

    @Transactional
    public InventoryTransferRequestResponse createRequest(CreateInventoryTransferRequest request) {
        AuthenticatedUser actor = requireInventoryActor();
        if (request == null
                || request.requestingBranchId() == null
                || request.sourceBranchId() == null
                || request.productId() == null
                || request.reason() == null) {
            throw invalidRequest("Los datos de la solicitud son requeridos.");
        }
        requireAccess(actor, request.requestingBranchId());
        if (Objects.equals(request.requestingBranchId(), request.sourceBranchId())) {
            throw invalidRequest("Las sucursales de origen y destino deben ser diferentes.");
        }
        requireOperationalBranch(actor.tenantId(), request.requestingBranchId());
        requireOperationalBranch(actor.tenantId(), request.sourceBranchId());
        Product product = requireProduct(actor.tenantId(), request.productId());
        requireTransferable(product);
        BigDecimal quantity = requireQuantity(request.requestedQuantity());
        Instant now = Instant.now();

        InventoryTransferRequest entity = InventoryTransferRequest.builder()
                .requestingBranchId(request.requestingBranchId())
                .sourceBranchId(request.sourceBranchId())
                .productId(product.getId())
                .requestedQuantity(quantity)
                .reason(request.reason())
                .notes(normalize(request.notes()))
                .status(InventoryTransferRequestStatus.requested)
                .requestedByUserId(actor.userId())
                .requestedAt(now)
                .build();
        entity.setTenantId(actor.tenantId());
        entity = requestRepository.saveAndFlush(entity);
        return requestResponse(entity, null);
    }

    public PageResponse<InventoryTransferRequestResponse> listRequests(
            UUID requestingBranchId,
            UUID sourceBranchId,
            InventoryTransferRequestStatus status,
            Pageable requestedPageable) {
        AuthenticatedUser actor = requireInventoryActor();
        validateOptionalBranch(actor.tenantId(), requestingBranchId);
        validateOptionalBranch(actor.tenantId(), sourceBranchId);
        BranchAccess access = branchAccessResolver.resolve(actor);
        Pageable pageable = safePageable(requestedPageable);
        Page<InventoryTransferRequest> page = access.allBranches()
                ? requestRepository.findPage(
                        actor.tenantId(), requestingBranchId, sourceBranchId, status, pageable)
                : accessibleRequestPage(
                        actor.tenantId(), access.branchIds(), requestingBranchId, sourceBranchId, status, pageable);

        Map<UUID, InventoryTransfer> transfersByRequest = transfersByRequest(
                actor.tenantId(), page.getContent().stream().map(InventoryTransferRequest::getId).toList());
        return PageResponse.from(page, request -> requestResponse(
                request, transfersByRequest.get(request.getId())));
    }

    @Transactional
    public InventoryTransferResponse approve(
            UUID requestId, ApproveInventoryTransferRequest approval) {
        AuthenticatedUser actor = requireInventoryActor();
        if (approval == null || normalize(approval.operationId()) == null) {
            throw invalidRequest("operationId es requerido.");
        }
        InventoryTransferRequest request = requireLockedRequest(actor.tenantId(), requestId);
        requireAccess(actor, request.getSourceBranchId());
        String operationId = normalize(approval.operationId());
        if (operationId.length() > 128) {
            throw invalidRequest("operationId no puede exceder 128 caracteres.");
        }
        String fingerprint = approvalFingerprint(request, approval);

        InventoryTransfer existing = transferRepository
                .findByTenantIdAndOperationId(actor.tenantId(), operationId)
                .orElse(null);
        if (existing != null) {
            return requireMatchingReplay(actor.tenantId(), request, existing, fingerprint);
        }
        if (request.getStatus() != InventoryTransferRequestStatus.requested) {
            throw invalidRequestState("La solicitud ya no puede aprobarse.");
        }

        requireOperationalBranch(actor.tenantId(), request.getRequestingBranchId());
        requireOperationalBranch(actor.tenantId(), request.getSourceBranchId());
        Product product = requireProduct(actor.tenantId(), request.getProductId());
        requireTransferable(product);
        // La ubicacion exacta se elige al recibir; aqui solo se exige que el destino pueda recibir el producto.
        operationalLocationService.requireTransferDestinationReceivable(
                actor.tenantId(), request.getRequestingBranchId(), List.of(request.getProductId()));
        BigDecimal quantity = requireQuantity(request.getRequestedQuantity());
        Instant now = Instant.now();

        InventoryTransfer transfer = InventoryTransfer.builder()
                .number(documentCounterService.nextInventoryTransferNumber(actor.tenantId()))
                .sourceBranchId(request.getSourceBranchId())
                .destinationBranchId(request.getRequestingBranchId())
                .status(InventoryTransferStatus.preparing)
                .operationId(operationId)
                .operationFingerprint(fingerprint)
                .reason(request.getReason())
                .notes(request.getNotes())
                .preparedByUserId(actor.userId())
                .preparedAt(now)
                .build();
        transfer.setTenantId(actor.tenantId());
        try {
            transfer = transferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict(
                    "INVENTORY_TRANSFER_OPERATION_CONFLICT",
                    "operationId ya fue utilizado por otra aprobación.");
        }

        InventoryTransferItem item = InventoryTransferItem.builder()
                .transferId(transfer.getId())
                .productId(request.getProductId())
                .sourceRequestId(request.getId())
                .requestedQuantity(quantity)
                .build();
        item.setTenantId(actor.tenantId());
        item = itemRepository.saveAndFlush(item);

        reservationLifecycleService.reserve(new ReserveInventoryCommand(
                actor.tenantId(),
                request.getSourceBranchId(),
                request.getProductId(),
                InventoryReservationSourceType.transfer,
                transfer.getId(),
                item.getId(),
                null,
                null,
                quantity));
        pickingService.ensureForTransfer(actor.tenantId(), transfer.getId());

        request.setStatus(InventoryTransferRequestStatus.approved);
        request.setReviewedByUserId(actor.userId());
        request.setReviewedAt(now);
        request.setReviewNotes(normalize(approval.reviewNotes()));
        requestRepository.saveAndFlush(request);
        return transferResponse(transfer, List.of(item));
    }

    @Transactional
    public InventoryTransferRequestResponse reject(
            UUID requestId, RejectInventoryTransferRequest rejection) {
        AuthenticatedUser actor = requireInventoryActor();
        InventoryTransferRequest request = requireLockedRequest(actor.tenantId(), requestId);
        requireAccess(actor, request.getSourceBranchId());
        requireRequested(request, "La solicitud ya no puede rechazarse.");
        reviewRequest(
                request,
                InventoryTransferRequestStatus.rejected,
                actor.userId(),
                rejection == null ? null : rejection.reviewNotes());
        return requestResponse(requestRepository.saveAndFlush(request), null);
    }

    @Transactional
    public InventoryTransferRequestResponse cancelRequest(
            UUID requestId, CancelInventoryTransferRequest cancellation) {
        AuthenticatedUser actor = requireInventoryActor();
        InventoryTransferRequest request = requireLockedRequest(actor.tenantId(), requestId);
        requireAccess(actor, request.getRequestingBranchId());
        requireRequested(
                request,
                "Solo una solicitud pendiente puede cancelarse; cancele la transferencia aprobada desde su propio recurso.");
        reviewRequest(
                request,
                InventoryTransferRequestStatus.cancelled,
                actor.userId(),
                cancellation == null ? null : cancellation.reason());
        return requestResponse(requestRepository.saveAndFlush(request), null);
    }

    public PageResponse<InventoryTransferResponse> listTransfers(
            UUID sourceBranchId,
            UUID destinationBranchId,
            InventoryTransferStatus status,
            Pageable requestedPageable) {
        AuthenticatedUser actor = requireInventoryActor();
        validateOptionalBranch(actor.tenantId(), sourceBranchId);
        validateOptionalBranch(actor.tenantId(), destinationBranchId);
        BranchAccess access = branchAccessResolver.resolve(actor);
        Pageable pageable = safePageable(requestedPageable);
        Page<InventoryTransfer> page = access.allBranches()
                ? transferRepository.findPage(
                        actor.tenantId(), sourceBranchId, destinationBranchId, status, pageable)
                : accessibleTransferPage(
                        actor.tenantId(), access.branchIds(), sourceBranchId, destinationBranchId, status, pageable);
        Map<UUID, List<InventoryTransferItem>> itemsByTransfer = itemsByTransfer(
                actor.tenantId(), page.getContent().stream().map(InventoryTransfer::getId).toList());
        return PageResponse.from(page, transfer -> transferResponse(
                transfer, itemsByTransfer.getOrDefault(transfer.getId(), List.of())));
    }

    public InventoryTransferResponse getTransfer(UUID transferId) {
        AuthenticatedUser actor = requireInventoryActor();
        InventoryTransfer transfer = transferRepository
                .findByTenantIdAndId(actor.tenantId(), transferId)
                .orElseThrow(InventoryTransferService::transferNotFound);
        requireAnyBranchAccess(actor, transfer.getSourceBranchId(), transfer.getDestinationBranchId());
        return transferResponse(
                transfer,
                itemRepository.findByTenantIdAndTransferIdOrderByIdAsc(
                        actor.tenantId(), transfer.getId()));
    }

    @Transactional
    public InventoryTransferResponse cancelTransfer(
            UUID transferId, CancelInventoryTransferRequest cancellation) {
        AuthenticatedUser actor = requireInventoryActor();
        InventoryTransfer transfer = transferRepository
                .findForUpdateByTenantIdAndId(actor.tenantId(), transferId)
                .orElseThrow(InventoryTransferService::transferNotFound);
        requireAccess(actor, transfer.getSourceBranchId());
        List<InventoryTransferItem> items = itemRepository
                .findByTenantIdAndTransferIdOrderByIdAsc(actor.tenantId(), transfer.getId());
        if (transfer.getStatus() != InventoryTransferStatus.preparing
                && transfer.getStatus() != InventoryTransferStatus.cancelled) {
            throw invalidTransferState("Solo una transferencia en preparación puede cancelarse.");
        }

        List<InventoryReservation> reservations = reservationRepository
                .findByTenantIdAndSourceTypeAndSourceId(
                        actor.tenantId(), InventoryReservationSourceType.transfer, transfer.getId());
        Map<UUID, InventoryReservation> reservationsByLine = reservations.stream()
                .collect(Collectors.toMap(InventoryReservation::getSourceLineId, Function.identity()));
        if (reservations.size() != items.size()) {
            throw inconsistentReservation();
        }
        List<InventoryReservation> orderedReservations = items.stream()
                .map(item -> requireMatchingReservation(transfer, item, reservationsByLine.get(item.getId())))
                .sorted(Comparator.comparing(InventoryReservation::getId))
                .toList();
        if (transfer.getStatus() == InventoryTransferStatus.cancelled) {
            if (orderedReservations.stream()
                    .anyMatch(reservation -> reservation.getStatus() != InventoryReservationStatus.released)) {
                throw inconsistentReservation();
            }
            return transferResponse(transfer, items);
        }
        releaseTransferPhysicalReservations(actor.tenantId(), transfer, items);
        for (InventoryReservation reservation : orderedReservations) {
            reservationLifecycleService.release(actor.tenantId(), reservation.getId());
        }
        transfer.setStatus(InventoryTransferStatus.cancelled);
        transfer.setCancelledByUserId(actor.userId());
        transfer.setCancelledAt(Instant.now());
        transfer.setCancelReason(cancellation == null ? null : normalize(cancellation.reason()));
        return transferResponse(transferRepository.saveAndFlush(transfer), items);
    }

    private void releaseTransferPhysicalReservations(
            UUID tenantId,
            InventoryTransfer transfer,
            List<InventoryTransferItem> transferItems) {
        PickingOrder picking = pickingOrderRepository
                .findByTenantIdAndSourceTypeAndSourceId(
                        tenantId, PickingSourceType.transfer, transfer.getId())
                .orElse(null);
        if (picking == null) return;
        Map<UUID, InventoryTransferItem> transferItemsById = transferItems.stream()
                .collect(Collectors.toMap(InventoryTransferItem::getId, Function.identity()));
        List<PickingItem> pickingItems = pickingItemRepository
                .findByTenantIdAndPickingOrderId(tenantId, picking.getId());
        for (PickingItem pickingItem : pickingItems.stream()
                .sorted(Comparator.comparing(PickingItem::getProductId)
                        .thenComparing(PickingItem::getId))
                .toList()) {
            InventoryTransferItem transferItem = transferItemsById.get(
                    pickingItem.getSourceLineId());
            if (transferItem == null
                    || !transferItem.getProductId().equals(pickingItem.getProductId())) {
                throw inconsistentReservation();
            }
            Product product = requireProduct(tenantId, pickingItem.getProductId());
            if (!isTraceable(product) || pickingItem.getPickedTraces() == null) continue;
            List<InventoryPhysicalSelection> physical =
                    physicalSelectionCodec.decode(pickingItem.getPickedTraces());
            List<InventoryTraceabilitySelection> selections =
                    physicalSelectionCodec.withoutLocation(
                            physical, pickingItem.getLocationId());
            traceabilityMutationService.releasePhysicalReservation(
                    tenantId,
                    transfer.getSourceBranchId(),
                    product,
                    pickingItem.getLocationId(),
                    selections);
        }
    }

    @Transactional
    public InventoryTransferReceiptResponse receiveTransfer(
            UUID transferId, ReceiveInventoryTransferRequest request) {
        AuthenticatedUser actor = requireInventoryActor();
        InventoryTransfer transfer = transferRepository
                .findForUpdateByTenantIdAndId(actor.tenantId(), transferId)
                .orElseThrow(InventoryTransferService::transferNotFound);
        requireAccess(actor, transfer.getDestinationBranchId());

        ReceiptPayload payload = requireReceiptPayload(transfer, request);
        InventoryTransferReceipt existing = receiptRepository
                .findByTenantIdAndConfirmationId(actor.tenantId(), payload.confirmationId())
                .orElse(null);
        if (existing != null) {
            return requireMatchingReceiptReplay(transfer, existing, payload.fingerprint());
        }
        if (transfer.getStatus() != InventoryTransferStatus.inTransit) {
            throw invalidTransferState(
                    "Solo una transferencia en tránsito puede recibir mercancía.");
        }

        requireOperationalBranch(actor.tenantId(), transfer.getDestinationBranchId());
        Location destinationLocation = requireDestinationLocation(
                actor.tenantId(), transfer.getDestinationBranchId(), payload.destinationLocationId());
        List<InventoryTransferItem> transferItems = itemRepository
                .findByTenantIdAndTransferIdOrderByIdAsc(actor.tenantId(), transfer.getId());
        Map<UUID, InventoryTransferItem> itemsById = transferItems.stream()
                .collect(Collectors.toMap(InventoryTransferItem::getId, Function.identity()));
        TransferTraceHistory traceHistory = transferTraceHistory(
                actor.tenantId(), transfer.getId());
        List<ResolvedReceiptItem> resolvedItems = payload.items().stream()
                .map(item -> resolveReceiptItem(
                        actor.tenantId(), transfer, itemsById, item, traceHistory))
                .toList();

        Instant now = Instant.now();
        InventoryTransferReceipt receipt = InventoryTransferReceipt.builder()
                .transferId(transfer.getId())
                .confirmationId(payload.confirmationId())
                .operationFingerprint(payload.fingerprint())
                .receivedByUserId(actor.userId())
                .receivedAt(now)
                .build();
        receipt.setTenantId(actor.tenantId());
        try {
            receipt = receiptRepository.saveAndFlush(receipt);
        } catch (DataIntegrityViolationException exception) {
            throw receiptConfirmationConflict();
        }

        List<InventoryTransferReceiptItem> receiptItems = new java.util.ArrayList<>();
        for (ResolvedReceiptItem resolved : resolvedItems) {
            InventoryTransferItem item = resolved.item();
            item.setReceivedQuantity(item.getReceivedQuantity().add(resolved.quantity()));
            itemRepository.save(item);

            InventoryTransferReceiptItem receiptItem = InventoryTransferReceiptItem.builder()
                    .receiptId(receipt.getId())
                    .transferItemId(item.getId())
                    .productId(item.getProductId())
                    .locationId(destinationLocation.getId())
                    .quantity(resolved.quantity())
                    .build();
            receiptItem.setTenantId(actor.tenantId());
            receiptItems.add(receiptItemRepository.save(receiptItem));

            var movement = inventoryStockService.incrementStockAtLocation(
                    new AddStockCommand(
                            actor.tenantId(),
                            transfer.getDestinationBranchId(),
                            item.getProductId(),
                            resolved.quantity(),
                            "Recepción de traslado " + transfer.getNumber(),
                            resolved.traceable() ? "receipt" : "transfer",
                            resolved.traceable() ? receipt.getId() : transfer.getId(),
                            resolved.traceable() ? item.getId() : null,
                            actor.userId()),
                    destinationLocation.getId());
            if (resolved.traceable()) {
                traceabilityMutationService.receiveTransferredPhysicalStock(
                        actor.tenantId(),
                        transfer.getDestinationBranchId(),
                        resolved.product(),
                        destinationLocation.getId(),
                        resolved.quantity(),
                        resolved.selections(),
                        movement.getId());
            }
        }

        if (!transferItems.isEmpty()
                && transferItems.stream().allMatch(item ->
                        item.getReceivedQuantity().compareTo(item.getDispatchedQuantity()) == 0)) {
            transfer.setStatus(InventoryTransferStatus.received);
            transfer.setReceivedByUserId(actor.userId());
            transfer.setReceivedAt(now);
        }
        transferRepository.saveAndFlush(transfer);
        return receiptResponse(
                receipt,
                receiptItems,
                transfer,
                destinationLocation.getId(),
                false);
    }

    private ReceiptPayload requireReceiptPayload(
            InventoryTransfer transfer, ReceiveInventoryTransferRequest request) {
        String confirmationId = request == null ? null : normalize(request.confirmationId());
        if (confirmationId == null
                || confirmationId.length() > 128
                || request.destinationLocationId() == null
                || request.items() == null
                || request.items().isEmpty()) {
            throw invalidReceipt("La confirmación, ubicación destino y líneas son requeridas.");
        }
        Set<UUID> seen = new java.util.HashSet<>();
        List<ReceiveInventoryTransferItemRequest> items = request.items().stream()
                .map(item -> {
                    if (item == null
                            || item.itemId() == null
                            || item.receivedQuantity() == null
                            || item.receivedQuantity().signum() <= 0
                            || !fitsDecimal(item.receivedQuantity(), 12, 3)
                            || item.receivedSelections() != null
                                    && item.receivedSelections().stream().anyMatch(Objects::isNull)) {
                        throw invalidReceipt(
                                "Cada línea debe identificar un ítem y una cantidad positiva de hasta tres decimales.");
                    }
                    if (!seen.add(item.itemId())) {
                        throw invalidReceipt("Una línea de transferencia no puede repetirse.");
                    }
                    return item;
                })
                .sorted(Comparator.comparing(ReceiveInventoryTransferItemRequest::itemId))
                .toList();
        return new ReceiptPayload(
                confirmationId,
                request.destinationLocationId(),
                items,
                receiptFingerprint(
                        transfer.getId(), request.destinationLocationId(), items));
    }

    private InventoryTransferReceiptResponse requireMatchingReceiptReplay(
            InventoryTransfer transfer,
            InventoryTransferReceipt receipt,
            String fingerprint) {
        if (!Objects.equals(receipt.getTransferId(), transfer.getId())
                || !Objects.equals(receipt.getOperationFingerprint(), fingerprint)) {
            throw receiptConfirmationConflict();
        }
        List<InventoryTransferReceiptItem> receiptItems = receiptItemRepository
                .findByTenantIdAndReceiptIdOrderByIdAsc(transfer.getTenantId(), receipt.getId());
        UUID locationId = receiptItems.stream()
                .map(InventoryTransferReceiptItem::getLocationId)
                .distinct()
                .reduce((first, second) -> {
                    throw receiptConfirmationConflict();
                })
                .orElseThrow(InventoryTransferService::receiptConfirmationConflict);
        return receiptResponse(receipt, receiptItems, transfer, locationId, true);
    }

    private Location requireDestinationLocation(
            UUID tenantId, UUID destinationBranchId, UUID locationId) {
        Location location = locationRepository
                .findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(InventoryTransferService::invalidDestinationLocation);
        if (!Objects.equals(location.getBranchId(), destinationBranchId)
                || location.getStatus() != LocationStatus.active) {
            throw invalidDestinationLocation();
        }
        return location;
    }

    private ResolvedReceiptItem resolveReceiptItem(
            UUID tenantId,
            InventoryTransfer transfer,
            Map<UUID, InventoryTransferItem> itemsById,
            ReceiveInventoryTransferItemRequest request,
            TransferTraceHistory traceHistory) {
        InventoryTransferItem item = itemsById.get(request.itemId());
        if (item == null || !Objects.equals(item.getTransferId(), transfer.getId())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_TRANSFER_RECEIPT_ITEM_NOT_FOUND",
                    "La línea no pertenece a la transferencia.");
        }
        Product product = requireProduct(tenantId, item.getProductId());
        requireTransferable(product);
        BigDecimal receivedAfter = item.getReceivedQuantity().add(request.receivedQuantity());
        if (item.getDispatchedQuantity().signum() <= 0
                || receivedAfter.compareTo(item.getDispatchedQuantity()) > 0) {
            throw BusinessException.conflict(
                    "INVENTORY_TRANSFER_OVER_RECEIPT",
                    "La cantidad recibida excede la cantidad despachada.");
        }
        boolean traceable = isTraceable(product);
        List<InventoryTransferTrackingSelectionRequest> requestedSelections =
                request.receivedSelections() == null ? List.of() : request.receivedSelections();
        if (!traceable) {
            if (!requestedSelections.isEmpty()) {
                throw invalidReceipt("El producto no utiliza trazabilidad fisica.");
            }
            return new ResolvedReceiptItem(
                    item, product, request.receivedQuantity(), List.of(), false);
        }
        if (requestedSelections.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_SELECTIONS_REQUIRED",
                    "La seleccion recibida es obligatoria para productos trazables.");
        }
        List<InventoryTraceabilitySelection> selections = requestedSelections.stream()
                .map(selection -> new InventoryTraceabilitySelection(
                        selection.lotId(), selection.quantity(), selection.serialNumbers()))
                .toList();
        validateReceivedTraceSelection(
                item, product, request.receivedQuantity(), selections, traceHistory);
        return new ResolvedReceiptItem(
                item, product, request.receivedQuantity(), selections, true);
    }

    private TransferTraceHistory transferTraceHistory(UUID tenantId, UUID transferId) {
        List<InventoryMovement> dispatched = movementRepository
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        tenantId, Set.of("transfer"), transferId);
        Map<UUID, List<InventoryHistoricalTraceDetail>> dispatchedHistory =
                traceabilityHistoryService.expand(tenantId, dispatched);
        List<InventoryTransferReceipt> receipts = receiptRepository
                .findByTenantIdAndTransferIdOrderByReceivedAtAsc(tenantId, transferId);
        List<InventoryMovement> received = receipts.isEmpty()
                ? List.of()
                : movementRepository
                        .findByTenantIdAndReferenceTypeInAndReferenceIdInOrderByCreatedAtAscIdAsc(
                                tenantId,
                                Set.of("receipt"),
                                receipts.stream().map(InventoryTransferReceipt::getId).toList());
        Map<UUID, List<InventoryHistoricalTraceDetail>> receivedHistory =
                traceabilityHistoryService.expand(tenantId, received);
        return new TransferTraceHistory(
                traceDetailsByLine(dispatched, dispatchedHistory),
                traceDetailsByLine(received, receivedHistory));
    }

    private static Map<UUID, List<InventoryHistoricalTraceDetail>> traceDetailsByLine(
            List<InventoryMovement> movements,
            Map<UUID, List<InventoryHistoricalTraceDetail>> history) {
        return movements.stream()
                .filter(movement -> movement.getReferenceLineId() != null)
                .collect(Collectors.groupingBy(
                        InventoryMovement::getReferenceLineId,
                        Collectors.flatMapping(
                                movement -> history.getOrDefault(
                                                movement.getId(), List.of())
                                        .stream(),
                                Collectors.toList())));
    }

    private static void validateReceivedTraceSelection(
            InventoryTransferItem item,
            Product product,
            BigDecimal receivedQuantity,
            List<InventoryTraceabilitySelection> selections,
            TransferTraceHistory history) {
        if (selections.stream().anyMatch(selection ->
                selection == null || selection.quantity() == null || selection.quantity().signum() <= 0)) {
            throw invalidReceipt("La seleccion trazable recibida es invalida.");
        }
        BigDecimal selected = selections.stream()
                .map(InventoryTraceabilitySelection::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (selected.compareTo(receivedQuantity) != 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La seleccion recibida no coincide con la cantidad confirmada.");
        }
        List<InventoryHistoricalTraceDetail> dispatched =
                history.dispatchedByLine().getOrDefault(item.getId(), List.of());
        List<InventoryHistoricalTraceDetail> previouslyReceived =
                history.receivedByLine().getOrDefault(item.getId(), List.of());
        if (dispatched.isEmpty()) {
            throw BusinessException.conflict(
                    "TRANSFER_TRACE_HISTORY_INCONSISTENT",
                    "El despacho trazable de la transferencia es inconsistente.");
        }
        if (Boolean.TRUE.equals(product.getTrackingSerial())) {
            Map<String, UUID> dispatchedSerials = serialLots(dispatched);
            Set<String> receivedSerials = serialLots(previouslyReceived).keySet();
            Set<String> requested = new java.util.HashSet<>();
            for (InventoryTraceabilitySelection selection : selections) {
                List<String> serials = selection.serialNumbers() == null
                        ? List.of()
                        : selection.serialNumbers().stream()
                                .map(value -> value == null ? null : value.trim())
                                .toList();
                if (serials.isEmpty()
                        || serials.stream().anyMatch(Objects::isNull)
                        || selection.quantity().stripTrailingZeros().scale() > 0
                        || selection.quantity().compareTo(BigDecimal.valueOf(serials.size())) != 0) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "SERIAL_COUNT_MISMATCH",
                            "La cantidad recibida debe coincidir con los seriales indicados.");
                }
                for (String serial : serials) {
                    UUID dispatchedLot = dispatchedSerials.get(serial);
                    if (serial.isEmpty()
                            || !requested.add(serial)
                            || dispatchedLot == null && !dispatchedSerials.containsKey(serial)
                            || receivedSerials.contains(serial)) {
                        throw BusinessException.conflict(
                                "TRANSFER_SERIAL_NOT_RECEIVABLE",
                                "El serial no fue despachado o ya fue recibido.");
                    }
                    if (!Objects.equals(dispatchedLot, selection.lotId())) {
                        throw invalidReceipt("El serial no coincide con su lote despachado.");
                    }
                    if (Boolean.TRUE.equals(product.getTrackingLot()) && selection.lotId() == null) {
                        throw invalidReceipt("El lote despachado del serial es requerido.");
                    }
                    if (!Boolean.TRUE.equals(product.getTrackingLot()) && selection.lotId() != null) {
                        throw invalidReceipt("El producto serializado no utiliza lote.");
                    }
                }
            }
            return;
        }

        Map<UUID, BigDecimal> dispatchedLots = quantitiesByLot(dispatched);
        Map<UUID, BigDecimal> receivedLots = quantitiesByLot(previouslyReceived);
        Map<UUID, BigDecimal> requestedLots = new HashMap<>();
        for (InventoryTraceabilitySelection selection : selections) {
            if (selection.lotId() == null
                    || selection.serialNumbers() != null && !selection.serialNumbers().isEmpty()) {
                throw invalidReceipt("La recepcion por lote requiere lote y no admite seriales.");
            }
            requestedLots.merge(selection.lotId(), selection.quantity(), BigDecimal::add);
        }
        requestedLots.forEach((lotId, quantity) -> {
            BigDecimal remaining = dispatchedLots.getOrDefault(lotId, BigDecimal.ZERO)
                    .subtract(receivedLots.getOrDefault(lotId, BigDecimal.ZERO));
            if (quantity.compareTo(remaining) > 0) {
                throw BusinessException.conflict(
                        "INVENTORY_TRANSFER_OVER_RECEIPT",
                        "La cantidad recibida del lote excede lo despachado pendiente.");
            }
        });
    }

    private static Map<String, UUID> serialLots(
            List<InventoryHistoricalTraceDetail> details) {
        Map<String, UUID> result = new HashMap<>();
        details.forEach(detail -> detail.serialNumbers().forEach(serial ->
                result.put(serial, detail.lotId())));
        return result;
    }

    private static Map<UUID, BigDecimal> quantitiesByLot(
            List<InventoryHistoricalTraceDetail> details) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        details.forEach(detail -> {
            if (detail.lotId() != null) {
                result.merge(detail.lotId(), detail.quantity(), BigDecimal::add);
            }
        });
        return result;
    }

    private static InventoryTransferReceiptResponse receiptResponse(
            InventoryTransferReceipt receipt,
            List<InventoryTransferReceiptItem> items,
            InventoryTransfer transfer,
            UUID destinationLocationId,
            boolean idempotent) {
        return new InventoryTransferReceiptResponse(
                receipt.getId(),
                receipt.getTransferId(),
                receipt.getConfirmationId(),
                transfer.getDestinationBranchId(),
                destinationLocationId,
                receipt.getReceivedByUserId(),
                receipt.getReceivedAt(),
                items.stream()
                        .map(InventoryTransferReceiptItemResponse::from)
                        .toList(),
                transfer.getStatus(),
                idempotent);
    }

    private static String receiptFingerprint(
            UUID transferId,
            UUID destinationLocationId,
            List<ReceiveInventoryTransferItemRequest> items) {
        String value = transferId
                + "|receive|"
                + destinationLocationId
                + items.stream()
                        .map(item -> "|"
                                + item.itemId()
                                + ":"
                                + item.receivedQuantity()
                                        .stripTrailingZeros()
                                        .toPlainString()
                                + ":"
                                + transferSelectionFingerprint(item.receivedSelections()))
                        .reduce("", String::concat);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static String transferSelectionFingerprint(
            List<InventoryTransferTrackingSelectionRequest> selections) {
        if (selections == null || selections.isEmpty()) return "-";
        return selections.stream()
                .map(selection -> selection == null
                        ? "<null>"
                        : selection.lotId()
                                + "/" + (selection.quantity() == null
                                        ? "null"
                                        : selection.quantity().stripTrailingZeros().toPlainString())
                                + "/" + (selection.serialNumbers() == null
                                        ? List.<String>of()
                                        : selection.serialNumbers()).stream()
                                                .map(value -> value == null ? "<null>" : value.trim())
                                                .sorted()
                                                .collect(Collectors.joining(";")))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private AuthenticatedUser requireInventoryActor() {
        AuthenticatedUser actor = currentUser.require();
        tenantCapabilityGuard.ensureTenantCapability(actor.tenantId(), SaasCapability.inventory);
        return actor;
    }

    private InventoryTransferRequest requireLockedRequest(UUID tenantId, UUID requestId) {
        return requestRepository
                .findForUpdateByTenantIdAndId(tenantId, requestId)
                .orElseThrow(InventoryTransferService::requestNotFound);
    }

    private Branch requireOperationalBranch(UUID tenantId, UUID branchId) {
        Branch branch = requireBranch(tenantId, branchId);
        if (branch.getStatus() != BranchStatus.active) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_TRANSFER_BRANCH_NOT_OPERATIONAL",
                    "La sucursal debe estar activa.");
        }
        return branch;
    }

    private void validateOptionalBranch(UUID tenantId, UUID branchId) {
        if (branchId != null) {
            requireBranch(tenantId, branchId);
        }
    }

    private Branch requireBranch(UUID tenantId, UUID branchId) {
        return branchRepository
                .findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository
                .findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private static void requireTransferable(Product product) {
        if (product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_TRANSFER_PRODUCT_UNSUPPORTED",
                    "Solo pueden transferirse productos físicos con control de inventario.");
        }
        if (!Boolean.TRUE.equals(product.getTrackingLot())
                && !Boolean.TRUE.equals(product.getTrackingSerial())
                && Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_TRANSFER_TRACEABILITY_UNSUPPORTED",
                    "Las transferencias con lote, serie o vencimiento aún no están soportadas.");
        }
    }

    private static boolean isTraceable(Product product) {
        return product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock())
                && (Boolean.TRUE.equals(product.getTrackingLot())
                        || Boolean.TRUE.equals(product.getTrackingSerial()));
    }

    private static BigDecimal requireQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0 || !fitsDecimal(quantity, 12, 3)) {
            throw invalidRequest("La cantidad solicitada debe ser positiva y admitir hasta tres decimales.");
        }
        return quantity;
    }

    private void requireAccess(AuthenticatedUser actor, UUID branchId) {
        if (!branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }

    private void requireAnyBranchAccess(AuthenticatedUser actor, UUID first, UUID second) {
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (!access.allows(first) && !access.allows(second)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta transferencia.");
        }
    }

    private static void requireRequested(InventoryTransferRequest request, String message) {
        if (request.getStatus() != InventoryTransferRequestStatus.requested) {
            throw invalidRequestState(message);
        }
    }

    private static void reviewRequest(
            InventoryTransferRequest request,
            InventoryTransferRequestStatus status,
            UUID reviewerId,
            String notes) {
        request.setStatus(status);
        request.setReviewedByUserId(reviewerId);
        request.setReviewedAt(Instant.now());
        request.setReviewNotes(normalize(notes));
    }

    private InventoryTransferResponse requireMatchingReplay(
            UUID tenantId,
            InventoryTransferRequest request,
            InventoryTransfer transfer,
            String fingerprint) {
        InventoryTransferItem item = itemRepository
                .findByTenantIdAndSourceRequestId(tenantId, request.getId())
                .orElse(null);
        if (!Objects.equals(transfer.getOperationFingerprint(), fingerprint)
                || item == null
                || !Objects.equals(item.getTransferId(), transfer.getId())) {
            throw BusinessException.conflict(
                    "INVENTORY_TRANSFER_OPERATION_CONFLICT",
                    "operationId ya fue utilizado con una operación diferente.");
        }
        return transferResponse(transfer, List.of(item));
    }

    private static InventoryReservation requireMatchingReservation(
            InventoryTransfer transfer,
            InventoryTransferItem item,
            InventoryReservation reservation) {
        if (reservation == null
                || !Objects.equals(reservation.getTenantId(), transfer.getTenantId())
                || !Objects.equals(reservation.getBranchId(), transfer.getSourceBranchId())
                || !Objects.equals(reservation.getSourceId(), transfer.getId())
                || !Objects.equals(reservation.getProductId(), item.getProductId())
                || reservation.getQuantity().compareTo(item.getRequestedQuantity()) != 0) {
            throw inconsistentReservation();
        }
        return reservation;
    }

    private Map<UUID, InventoryTransfer> transfersByRequest(
            UUID tenantId, Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        List<InventoryTransferItem> items = itemRepository
                .findByTenantIdAndSourceRequestIdIn(tenantId, requestIds);
        if (items.isEmpty()) {
            return Map.of();
        }
        Map<UUID, InventoryTransferItem> itemsByRequest = items.stream()
                .collect(Collectors.toMap(InventoryTransferItem::getSourceRequestId, Function.identity()));
        Map<UUID, InventoryTransfer> transfersById = transferRepository
                .findByTenantIdAndIdIn(
                        tenantId, items.stream().map(InventoryTransferItem::getTransferId).toList())
                .stream()
                .collect(Collectors.toMap(InventoryTransfer::getId, Function.identity()));
        Map<UUID, InventoryTransfer> result = new HashMap<>();
        itemsByRequest.forEach((requestId, item) -> {
            InventoryTransfer transfer = transfersById.get(item.getTransferId());
            if (transfer != null) {
                result.put(requestId, transfer);
            }
        });
        return result;
    }

    private Map<UUID, List<InventoryTransferItem>> itemsByTransfer(
            UUID tenantId, Collection<UUID> transferIds) {
        if (transferIds.isEmpty()) {
            return Map.of();
        }
        return itemRepository
                .findByTenantIdAndTransferIdInOrderByTransferIdAscIdAsc(tenantId, transferIds)
                .stream()
                .collect(Collectors.groupingBy(
                        InventoryTransferItem::getTransferId, Collectors.toList()));
    }

    private static InventoryTransferRequestResponse requestResponse(
            InventoryTransferRequest request, InventoryTransfer transfer) {
        return new InventoryTransferRequestResponse(
                request.getId(),
                request.getRequestingBranchId(),
                request.getSourceBranchId(),
                request.getProductId(),
                request.getRequestedQuantity(),
                request.getReason(),
                request.getNotes(),
                request.getStatus(),
                effectiveStatus(request, transfer),
                request.getRequestedByUserId(),
                request.getRequestedAt(),
                request.getReviewedByUserId(),
                request.getReviewedAt(),
                request.getReviewNotes(),
                transfer == null ? null : transfer.getId(),
                request.getVersion(),
                request.getCreatedAt(),
                request.getUpdatedAt());
    }

    private static InventoryTransferRequestEffectiveStatus effectiveStatus(
            InventoryTransferRequest request, InventoryTransfer transfer) {
        if (transfer != null) {
            return switch (transfer.getStatus()) {
                case preparing -> InventoryTransferRequestEffectiveStatus.approved;
                case inTransit -> InventoryTransferRequestEffectiveStatus.inTransit;
                case received -> InventoryTransferRequestEffectiveStatus.received;
                case cancelled -> InventoryTransferRequestEffectiveStatus.cancelled;
            };
        }
        return InventoryTransferRequestEffectiveStatus.valueOf(request.getStatus().name());
    }

    private static InventoryTransferResponse transferResponse(
            InventoryTransfer transfer, List<InventoryTransferItem> items) {
        return new InventoryTransferResponse(
                transfer.getId(),
                transfer.getNumber(),
                transfer.getSourceBranchId(),
                transfer.getDestinationBranchId(),
                transfer.getStatus(),
                transfer.getReason(),
                transfer.getNotes(),
                items.stream().map(InventoryTransferItemResponse::from).toList(),
                transfer.getPreparedByUserId(),
                transfer.getPreparedAt(),
                transfer.getDispatchedByUserId(),
                transfer.getDispatchedAt(),
                transfer.getReceivedByUserId(),
                transfer.getReceivedAt(),
                transfer.getCancelledByUserId(),
                transfer.getCancelledAt(),
                transfer.getCancelReason(),
                transfer.getVersion(),
                transfer.getCreatedAt(),
                transfer.getUpdatedAt());
    }

    private Page<InventoryTransferRequest> accessibleRequestPage(
            UUID tenantId,
            Set<UUID> branchIds,
            UUID requestingBranchId,
            UUID sourceBranchId,
            InventoryTransferRequestStatus status,
            Pageable pageable) {
        if (branchIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return requestRepository.findPageForBranches(
                tenantId, branchIds, requestingBranchId, sourceBranchId, status, pageable);
    }

    private Page<InventoryTransfer> accessibleTransferPage(
            UUID tenantId,
            Set<UUID> branchIds,
            UUID sourceBranchId,
            UUID destinationBranchId,
            InventoryTransferStatus status,
            Pageable pageable) {
        if (branchIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return transferRepository.findPageForBranches(
                tenantId, branchIds, sourceBranchId, destinationBranchId, status, pageable);
    }

    private static Pageable safePageable(Pageable requested) {
        int size = Math.min(Math.max(requested.getPageSize(), 1), 100);
        return PageRequest.of(
                Math.max(requested.getPageNumber(), 0),
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    }

    private static String approvalFingerprint(
            InventoryTransferRequest request, ApproveInventoryTransferRequest approval) {
        String value = request.getId()
                + "|approve|"
                + request.getSourceBranchId()
                + "|"
                + request.getRequestingBranchId()
                + "|"
                + request.getProductId()
                + "|"
                + request.getRequestedQuantity().stripTrailingZeros().toPlainString()
                + "|"
                + request.getReason()
                + "|"
                + Objects.toString(normalize(approval.reviewNotes()), "");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static boolean fitsDecimal(BigDecimal value, int precision, int scale) {
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionDigits <= scale && integerDigits <= precision - scale;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static BusinessException requestNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "INVENTORY_TRANSFER_REQUEST_NOT_FOUND",
                "Solicitud de transferencia no encontrada.");
    }

    private static BusinessException transferNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "INVENTORY_TRANSFER_NOT_FOUND",
                "Transferencia de inventario no encontrada.");
    }

    private static BusinessException invalidRequest(String message) {
        return new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_TRANSFER_REQUEST", message);
    }

    private static BusinessException invalidRequestState(String message) {
        return BusinessException.conflict("INVALID_INVENTORY_TRANSFER_REQUEST_STATE", message);
    }

    private static BusinessException invalidTransferState(String message) {
        return BusinessException.conflict("INVALID_INVENTORY_TRANSFER_STATE", message);
    }

    private static BusinessException inconsistentReservation() {
        return BusinessException.conflict(
                "INVENTORY_TRANSFER_RESERVATION_INCONSISTENT",
                "Las reservas de la transferencia no coinciden con sus líneas.");
    }

    private static BusinessException invalidReceipt(String message) {
        return new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_TRANSFER_RECEIPT", message);
    }

    private static BusinessException invalidDestinationLocation() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVENTORY_TRANSFER_DESTINATION_LOCATION_INVALID",
                "La ubicación destino no está disponible para esta transferencia.");
    }

    private static BusinessException receiptConfirmationConflict() {
        return BusinessException.conflict(
                "INVENTORY_TRANSFER_RECEIPT_CONFIRMATION_CONFLICT",
                "confirmationId ya fue utilizado con una recepción diferente.");
    }

    private record ReceiptPayload(
            String confirmationId,
            UUID destinationLocationId,
            List<ReceiveInventoryTransferItemRequest> items,
            String fingerprint) {}

    private record ResolvedReceiptItem(
            InventoryTransferItem item,
            Product product,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections,
            boolean traceable) {}

    private record TransferTraceHistory(
            Map<UUID, List<InventoryHistoricalTraceDetail>> dispatchedByLine,
            Map<UUID, List<InventoryHistoricalTraceDetail>> receivedByLine) {}
}
