package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.InventoryTransferItemResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestEffectiveStatus;
import com.omniretail.backend.inventory.dto.InventoryTransferRequestResponse;
import com.omniretail.backend.inventory.dto.InventoryTransferResponse;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferItem;
import com.omniretail.backend.inventory.entity.InventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryTransferItemRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.inventory.repository.InventoryTransferRequestRepository;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.logistics.service.PickingService;
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
    private final ProductRepository productRepository;
    private final InventoryTransferRequestRepository requestRepository;
    private final InventoryTransferRepository transferRepository;
    private final InventoryTransferItemRepository itemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationLifecycleService reservationLifecycleService;
    private final DocumentCounterService documentCounterService;
    private final PickingService pickingService;

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
        for (InventoryReservation reservation : orderedReservations) {
            reservationLifecycleService.release(actor.tenantId(), reservation.getId());
        }
        transfer.setStatus(InventoryTransferStatus.cancelled);
        transfer.setCancelledByUserId(actor.userId());
        transfer.setCancelledAt(Instant.now());
        transfer.setCancelReason(cancellation == null ? null : normalize(cancellation.reason()));
        return transferResponse(transferRepository.saveAndFlush(transfer), items);
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
        if (Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial())
                || Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_TRANSFER_TRACEABILITY_UNSUPPORTED",
                    "Las transferencias con lote, serie o vencimiento aún no están soportadas.");
        }
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
}
