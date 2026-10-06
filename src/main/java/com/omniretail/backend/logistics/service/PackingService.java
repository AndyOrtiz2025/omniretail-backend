package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.inventory.repository.InventoryTransferRepository;
import com.omniretail.backend.logistics.dto.PackingActionResponse;
import com.omniretail.backend.logistics.dto.PackingChecklistResponse;
import com.omniretail.backend.logistics.dto.PackingDetailResponse;
import com.omniretail.backend.logistics.dto.PackingFinalizeResponse;
import com.omniretail.backend.logistics.dto.PackingPreparedContentResponse;
import com.omniretail.backend.logistics.dto.PackingQueueResponse;
import com.omniretail.backend.logistics.dto.PackingVersionedRequest;
import com.omniretail.backend.logistics.dto.RegisterPackingLabelPrintRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.logistics.entity.Packing;
import com.omniretail.backend.logistics.entity.PackingOperation;
import com.omniretail.backend.logistics.entity.PackingOperationType;
import com.omniretail.backend.logistics.entity.PackingSourceType;
import com.omniretail.backend.logistics.entity.PackingStatus;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.logistics.repository.PackingOperationRepository;
import com.omniretail.backend.logistics.repository.PackingRepository;
import com.omniretail.backend.logistics.repository.PickingItemRepository;
import com.omniretail.backend.logistics.repository.PickingOrderRepository;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackingService {

    private final PackingRepository packingRepository;
    private final PackingOperationRepository operationRepository;
    private final PickingOrderRepository pickingOrderRepository;
    private final PickingItemRepository pickingItemRepository;
    private final OrderRepository orderRepository;
    private final InventoryTransferRepository transferRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final JsonMapper jsonMapper;

    public List<PackingQueueResponse> getQueue(UUID branchId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        return packingRepository
                .findByTenantIdAndBranchIdAndStatusOrderByCreatedAtAsc(
                        actor.tenantId(), branchId, PackingStatus.in_progress)
                .stream()
                .map(packing -> queueResponse(actor.tenantId(), packing))
                .toList();
    }

    public PackingDetailResponse getDetail(UUID branchId, UUID packingId) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Packing packing = findScoped(actor.tenantId(), branchId, packingId);
        return detail(actor.tenantId(), packing);
    }

    @Transactional
    public PackingActionResponse savePreparation(
            UUID branchId, UUID packingId, SavePackingPreparationRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Packing packing = lockScoped(actor.tenantId(), branchId, packingId);
        String operationId = request.operationId().trim();
        String fingerprint = fingerprint(
                packingId,
                PackingOperationType.save_preparation,
                actor.userId(),
                request.checklist().packageProtectionChecked()
                        + "|" + request.checklist().documentIncludedChecked()
                        + "|" + request.checklist().recipientVerifiedChecked()
                        + "|" + decimal(request.totalWeight())
                        + "|" + String.valueOf(request.packageCount()));
        Optional<PackingOperation> retry = operationRepository
                .findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (retry.isPresent()) return replayAction(retry.get(), packingId, fingerprint,
                PackingOperationType.save_preparation);

        MutationContext context = mutableContext(packing, request.expectedVersion());
        boolean labelDataChanged = !equalDecimal(packing.getTotalWeight(), request.totalWeight())
                || !java.util.Objects.equals(packing.getPackageCount(), request.packageCount());
        packing.setPackageProtectionChecked(request.checklist().packageProtectionChecked());
        packing.setDocumentIncludedChecked(request.checklist().documentIncludedChecked());
        packing.setRecipientVerifiedChecked(request.checklist().recipientVerifiedChecked());
        packing.setTotalWeight(request.totalWeight());
        packing.setPackageCount(request.packageCount());
        if (labelDataChanged) clearLabel(packing);
        packing = packingRepository.saveAndFlush(packing);

        PackingActionResponse response = new PackingActionResponse(
                detail(actor.tenantId(), packing, context), false);
        recordOperation(actor, packing, operationId, PackingOperationType.save_preparation,
                fingerprint, response);
        return response;
    }

    @Transactional
    public PackingActionResponse generateLabel(
            UUID branchId, UUID packingId, PackingVersionedRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Packing packing = lockScoped(actor.tenantId(), branchId, packingId);
        String operationId = request.operationId().trim();
        String fingerprint = fingerprint(
                packingId, PackingOperationType.generate_label, actor.userId(), "");
        Optional<PackingOperation> retry = operationRepository
                .findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (retry.isPresent()) return replayAction(retry.get(), packingId, fingerprint,
                PackingOperationType.generate_label);

        MutationContext context = mutableContext(packing, request.expectedVersion());
        requireReadyForLabel(packing);
        long nextVersion = packing.getVersion() + 1L;
        Instant now = Instant.now();
        packing.setLabelGenerationId(UUID.randomUUID().toString());
        packing.setLabelCode("LBL-" + context.sourceReference() + "-" + nextVersion);
        packing.setLabelGeneratedAt(now);
        packing.setLabelPrintedAt(null);
        packing = packingRepository.saveAndFlush(packing);

        PackingActionResponse response = new PackingActionResponse(
                detail(actor.tenantId(), packing, context), false);
        recordOperation(actor, packing, operationId, PackingOperationType.generate_label,
                fingerprint, response);
        return response;
    }

    @Transactional
    public PackingActionResponse registerLabelPrint(
            UUID branchId, UUID packingId, RegisterPackingLabelPrintRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Packing packing = lockScoped(actor.tenantId(), branchId, packingId);
        String operationId = request.operationId().trim();
        String generationId = request.labelGenerationId().trim();
        String fingerprint = fingerprint(
                packingId, PackingOperationType.register_label_print, actor.userId(), generationId);
        Optional<PackingOperation> retry = operationRepository
                .findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (retry.isPresent()) return replayAction(retry.get(), packingId, fingerprint,
                PackingOperationType.register_label_print);

        MutationContext context = mutableContext(packing, request.expectedVersion());
        if (!generationId.equals(packing.getLabelGenerationId())) {
            throw conflict("PACKING_LABEL_STALE", "La generación de etiqueta no coincide.");
        }
        if (packing.getLabelGeneratedAt() == null || packing.getLabelCode() == null) {
            throw conflict("PACKING_LABEL_NOT_GENERATED", "La etiqueta aún no ha sido generada.");
        }
        if (packing.getLabelPrintedAt() == null) {
            packing.setLabelPrintedAt(Instant.now());
            packing = packingRepository.saveAndFlush(packing);
        }

        PackingActionResponse response = new PackingActionResponse(
                detail(actor.tenantId(), packing, context), false);
        recordOperation(actor, packing, operationId, PackingOperationType.register_label_print,
                fingerprint, response);
        return response;
    }

    @Transactional
    public PackingFinalizeResponse finalizePacking(
            UUID branchId, UUID packingId, PackingVersionedRequest request) {
        AuthenticatedUser actor = actorForBranch(branchId);
        Packing packing = lockScoped(actor.tenantId(), branchId, packingId);
        String operationId = request.operationId().trim();
        String fingerprint = fingerprint(
                packingId, PackingOperationType.finalize, actor.userId(), "");
        Optional<PackingOperation> retry = operationRepository
                .findByTenantIdAndOperationId(actor.tenantId(), operationId);
        if (retry.isPresent()) return replayFinalize(retry.get(), packingId, fingerprint);

        MutationContext context = mutableContext(packing, request.expectedVersion());
        requireReadyForLabel(packing);
        if (packing.getLabelGenerationId() == null || packing.getLabelGeneratedAt() == null
                || packing.getLabelCode() == null) {
            throw conflict("PACKING_LABEL_NOT_GENERATED", "La etiqueta aún no ha sido generada.");
        }
        if (packing.getLabelPrintedAt() == null) {
            throw conflict("PACKING_LABEL_NOT_PRINTED", "La impresión de etiqueta es obligatoria.");
        }

        Instant now = Instant.now();
        packing.setStatus(PackingStatus.finalized);
        packing.setFinalizedByUserId(actor.userId());
        packing.setFinalizedAt(now);
        if (context.order() != null) {
            context.order().setStatus(
                    context.order().getDeliveryMethod() == DeliveryMethod.store_pickup
                            ? OrderStatus.ready_for_pickup
                            : OrderStatus.ready_for_dispatch);
            orderRepository.save(context.order());
        }
        packing = packingRepository.saveAndFlush(packing);

        PackingFinalizeResponse response = new PackingFinalizeResponse(
                detail(actor.tenantId(), packing, context),
                false,
                context.order() == null ? null : context.order().getStatus(),
                context.transfer() == null ? null : context.transfer().getStatus());
        recordOperation(actor, packing, operationId, PackingOperationType.finalize,
                fingerprint, response);
        return response;
    }

    private MutationContext mutableContext(Packing packing, Long expectedVersion) {
        requireSupportedSource(packing);
        if (!packing.getVersion().equals(expectedVersion)) {
            throw conflict("PACKING_VERSION_CONFLICT", "La versión de Packing está desactualizada.");
        }
        if (packing.getStatus() != PackingStatus.in_progress) {
            throw conflict("INVALID_PACKING_STATE", "El Packing se encuentra en un estado terminal.");
        }
        PickingOrder picking = requirePicking(packing);
        if (packing.getSourceType() == PackingSourceType.order) {
            Order order = lockOrder(packing);
            if (order.getStatus() != OrderStatus.packing) {
                throw conflict("INVALID_ORDER_STATUS_TRANSITION", "El pedido no se encuentra en Packing.");
            }
            return new MutationContext(order, null, picking, order.getOrderNumber());
        }
        InventoryTransfer transfer = lockTransfer(packing);
        return new MutationContext(null, transfer, picking, transfer.getNumber());
    }

    private Order lockOrder(Packing packing) {
        Order order = orderRepository.findByTenantIdAndIdForUpdate(
                        packing.getTenantId(), packing.getOrderId())
                .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
        requireMatchingOrder(packing, order);
        return order;
    }

    private InventoryTransfer lockTransfer(Packing packing) {
        tenantCapabilityGuard.ensureTenantCapability(
                packing.getTenantId(), SaasCapability.inventory);
        InventoryTransfer transfer = transferRepository
                .findForUpdateByTenantIdAndId(packing.getTenantId(), packing.getSourceId())
                .orElseThrow(() -> notFound(
                        "INVENTORY_TRANSFER_NOT_FOUND", "Transferencia no encontrada."));
        requireMatchingTransfer(packing, transfer);
        if (transfer.getStatus() != InventoryTransferStatus.preparing) {
            throw conflict(
                    "PACKING_TRANSFER_STATE_CONFLICT",
                    "La transferencia no se encuentra en preparación.");
        }
        return transfer;
    }

    private PickingOrder requirePicking(Packing packing) {
        PickingOrder picking = pickingOrderRepository
                .findByTenantIdAndBranchIdAndId(
                        packing.getTenantId(), packing.getBranchId(), packing.getPickingOrderId())
                .orElseThrow(() -> notFound("PICKING_NOT_FOUND", "Picking no encontrado."));
        PickingSourceType expectedSource = PickingSourceType.valueOf(packing.getSourceType().name());
        if (picking.getStatus() != PickingStatus.completed
                || picking.getSourceType() != expectedSource
                || !packing.getSourceId().equals(picking.getSourceId())
                || !java.util.Objects.equals(packing.getOrderId(), picking.getOrderId())) {
            throw conflict("PACKING_PICKING_CONFLICT", "Packing requiere un Picking completado válido.");
        }
        return picking;
    }

    private PackingDetailResponse detail(UUID tenantId, Packing packing) {
        requireSupportedSource(packing);
        PickingOrder picking = requirePicking(packing);
        if (packing.getSourceType() == PackingSourceType.order) {
            Order order = orderRepository.findByTenantIdAndId(tenantId, packing.getOrderId())
                    .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
            requireMatchingOrder(packing, order);
            return detail(
                    tenantId,
                    packing,
                    new MutationContext(order, null, picking, order.getOrderNumber()));
        }
        InventoryTransfer transfer = transferRepository
                .findByTenantIdAndId(tenantId, packing.getSourceId())
                .orElseThrow(() -> notFound(
                        "INVENTORY_TRANSFER_NOT_FOUND", "Transferencia no encontrada."));
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        requireMatchingTransfer(packing, transfer);
        return detail(
                tenantId,
                packing,
                new MutationContext(null, transfer, picking, transfer.getNumber()));
    }

    private PackingDetailResponse detail(
            UUID tenantId, Packing packing, MutationContext context) {
        PackingQueueResponse queue = queueResponse(tenantId, packing, context);
        return new PackingDetailResponse(
                queue.packingId(), queue.orderId(), queue.orderReference(), queue.customerName(),
                queue.storePickupContact(), queue.deliveryMethod(), queue.sourceType(), queue.sourceId(),
                queue.status(), queue.version(), queue.startedAt(), queue.updatedAt(),
                context.picking().getId(),
                context.order() == null ? null : context.order().getStatus(),
                context.order() == null ? null : json(context.order().getDeliveryAddress()),
                new PackingChecklistResponse(
                        packing.isPackageProtectionChecked(),
                        packing.isDocumentIncludedChecked(),
                        packing.isRecipientVerifiedChecked()),
                packing.getTotalWeight(), packing.getPackageCount(), packing.getLabelGenerationId(),
                packing.getLabelCode(), packing.getLabelGeneratedAt(), packing.getLabelPrintedAt(),
                packing.getFinalizedAt(),
                preparedContents(tenantId, packing, context.picking()),
                context.sourceReference());
    }

    private PackingQueueResponse queueResponse(UUID tenantId, Packing packing) {
        requireSupportedSource(packing);
        if (packing.getSourceType() == PackingSourceType.order) {
            Order order = orderRepository.findByTenantIdAndId(tenantId, packing.getOrderId())
                    .orElseThrow(() -> notFound("ORDER_NOT_FOUND", "Pedido no encontrado."));
            requireMatchingOrder(packing, order);
            return queueResponse(
                    tenantId,
                    packing,
                    new MutationContext(order, null, requirePicking(packing), order.getOrderNumber()));
        }
        InventoryTransfer transfer = transferRepository
                .findByTenantIdAndId(tenantId, packing.getSourceId())
                .orElseThrow(() -> notFound(
                        "INVENTORY_TRANSFER_NOT_FOUND", "Transferencia no encontrada."));
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        requireMatchingTransfer(packing, transfer);
        return queueResponse(
                tenantId,
                packing,
                new MutationContext(null, transfer, requirePicking(packing), transfer.getNumber()));
    }

    private PackingQueueResponse queueResponse(
            UUID tenantId, Packing packing, MutationContext context) {
        requireCoherentState(packing, context);
        Order order = context.order();
        return new PackingQueueResponse(
                packing.getId(),
                order == null ? null : order.getId(),
                order == null ? null : order.getOrderNumber(),
                order == null ? null : customerName(tenantId, order),
                null,
                order == null ? null : order.getDeliveryMethod(),
                packing.getSourceType(),
                packing.getSourceId(),
                packing.getStatus(),
                packing.getVersion(),
                packing.getStartedAt(),
                packing.getUpdatedAt(),
                context.sourceReference());
    }

    private List<PackingPreparedContentResponse> preparedContents(
            UUID tenantId, Packing packing, PickingOrder picking) {
        List<PickingItem> items = pickingItemRepository.findByScopeAndPickingOrderId(
                tenantId, packing.getBranchId(), picking.getId());
        Map<UUID, Product> products = productRepository
                .findByTenantIdAndIdIn(tenantId, items.stream().map(PickingItem::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return items.stream()
                .filter(item -> item.getPickedQuantity().signum() > 0)
                .map(item -> {
                    Product product = products.get(item.getProductId());
                    if (product == null) {
                        throw conflict("PACKING_PRODUCT_NOT_FOUND", "Producto de Packing no encontrado.");
                    }
                    return new PackingPreparedContentResponse(
                            item.getProductId(), product.getSku(), product.getName(),
                            item.getPickedQuantity(), List.of());
                })
                .toList();
    }

    private void recordOperation(
            AuthenticatedUser actor,
            Packing packing,
            String operationId,
            PackingOperationType type,
            String fingerprint,
            Object result) {
        operationRepository.saveAndFlush(PackingOperation.builder()
                .tenantId(actor.tenantId())
                .branchId(packing.getBranchId())
                .packingId(packing.getId())
                .operationId(operationId)
                .operationType(type)
                .fingerprint(fingerprint)
                .resultVersion(packing.getVersion())
                .resultPacking(jsonMapper.writeValueAsString(result))
                .build());
    }

    private PackingActionResponse replayAction(
            PackingOperation operation,
            UUID packingId,
            String fingerprint,
            PackingOperationType type) {
        requireMatchingOperation(operation, packingId, fingerprint, type);
        PackingActionResponse historical =
                jsonMapper.readValue(operation.getResultPacking(), PackingActionResponse.class);
        return new PackingActionResponse(historical.packing(), true);
    }

    private PackingFinalizeResponse replayFinalize(
            PackingOperation operation, UUID packingId, String fingerprint) {
        requireMatchingOperation(operation, packingId, fingerprint, PackingOperationType.finalize);
        PackingFinalizeResponse historical =
                jsonMapper.readValue(operation.getResultPacking(), PackingFinalizeResponse.class);
        return new PackingFinalizeResponse(
                historical.packing(),
                true,
                historical.orderStatus(),
                historical.transferStatus());
    }

    private static void requireMatchingOperation(
            PackingOperation operation,
            UUID packingId,
            String fingerprint,
            PackingOperationType type) {
        if (!packingId.equals(operation.getPackingId())
                || type != operation.getOperationType()
                || !fingerprint.equals(operation.getFingerprint())) {
            throw conflict(
                    "PACKING_OPERATION_ID_REUSED",
                    "El operationId ya fue utilizado con una mutación diferente.");
        }
    }

    private AuthenticatedUser actorForBranch(UUID branchId) {
        AuthenticatedUser actor = currentUser.require();
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        return actor;
    }

    private Packing findScoped(UUID tenantId, UUID branchId, UUID id) {
        return packingRepository.findByTenantIdAndBranchIdAndId(tenantId, branchId, id)
                .orElseThrow(() -> notFound("PACKING_NOT_FOUND", "Packing no encontrado."));
    }

    private Packing lockScoped(UUID tenantId, UUID branchId, UUID id) {
        return packingRepository.findByScopeAndIdForUpdate(tenantId, branchId, id)
                .orElseThrow(() -> notFound("PACKING_NOT_FOUND", "Packing no encontrado."));
    }

    private static void requireSupportedSource(Packing packing) {
        boolean order = packing.getSourceType() == PackingSourceType.order
                && packing.getOrderId() != null
                && packing.getOrderId().equals(packing.getSourceId());
        boolean transfer = packing.getSourceType() == PackingSourceType.transfer
                && packing.getOrderId() == null;
        if (!order && !transfer) {
            throw conflict(
                    "PACKING_SOURCE_NOT_SUPPORTED",
                    "La fuente de Packing solicitada no es válida.");
        }
    }

    private static void requireMatchingOrder(Packing packing, Order order) {
        if (!packing.getSourceId().equals(order.getId())
                || !packing.getOrderId().equals(order.getId())
                || !packing.getBranchId().equals(order.getBranchId())) {
            throw conflict("PACKING_SOURCE_CONFLICT", "Packing no coincide con su pedido.");
        }
        if (!isEligibleOrderSource(order)) {
            throw conflict(
                    "PACKING_ORDER_NOT_ELIGIBLE",
                    "El pedido no admite Packing.");
        }
    }

    private static boolean isEligibleOrderSource(Order order) {
        return (order.getSource() == OrderSource.ecommerce
                        && order.getDeliveryMethod() == DeliveryMethod.home_delivery)
                || (order.getSource() == OrderSource.pos
                        && (order.getDeliveryMethod() == DeliveryMethod.home_delivery
                                || order.getDeliveryMethod() == DeliveryMethod.store_pickup));
    }

    private static void requireMatchingTransfer(
            Packing packing, InventoryTransfer transfer) {
        if (packing.getSourceType() != PackingSourceType.transfer
                || packing.getOrderId() != null
                || !packing.getSourceId().equals(transfer.getId())
                || !packing.getBranchId().equals(transfer.getSourceBranchId())) {
            throw conflict("PACKING_SOURCE_CONFLICT", "Packing no coincide con su transferencia.");
        }
    }

    private static void requireCoherentState(
            Packing packing, MutationContext context) {
        if (context.transfer() != null) {
            boolean coherent = packing.getStatus() == PackingStatus.in_progress
                    ? context.transfer().getStatus() == InventoryTransferStatus.preparing
                    : context.transfer().getStatus() == InventoryTransferStatus.preparing
                            || context.transfer().getStatus() == InventoryTransferStatus.inTransit
                            || context.transfer().getStatus() == InventoryTransferStatus.received;
            if (!coherent) {
                throw conflict(
                        "PACKING_TRANSFER_STATE_CONFLICT",
                        "Packing y transferencia tienen estados incompatibles.");
            }
            return;
        }
        Order order = context.order();
        if (packing.getStatus() == PackingStatus.in_progress
                && order.getStatus() != OrderStatus.packing) {
            throw conflict("PACKING_ORDER_STATE_CONFLICT", "Packing y pedido tienen estados incompatibles.");
        }
        boolean finalizedOrderState = order.getDeliveryMethod() == DeliveryMethod.store_pickup
                ? order.getStatus() == OrderStatus.ready_for_pickup
                        || order.getStatus() == OrderStatus.delivered
                : order.getStatus() == OrderStatus.ready_for_dispatch
                        || order.getStatus() == OrderStatus.dispatched
                        || order.getStatus() == OrderStatus.delivered;
        if (packing.getStatus() == PackingStatus.finalized && !finalizedOrderState) {
            throw conflict("PACKING_ORDER_STATE_CONFLICT", "Packing y pedido tienen estados incompatibles.");
        }
    }

    private static void requireReadyForLabel(Packing packing) {
        if (!packing.isPackageProtectionChecked()
                || !packing.isDocumentIncludedChecked()
                || !packing.isRecipientVerifiedChecked()) {
            throw conflict("PACKING_CHECKLIST_INCOMPLETE", "El checklist de Packing está incompleto.");
        }
        if (packing.getTotalWeight() == null || packing.getTotalWeight().signum() <= 0
                || packing.getPackageCount() == null || packing.getPackageCount() < 1) {
            throw conflict(
                    "PACKING_MEASUREMENTS_REQUIRED",
                    "Peso total y cantidad de paquetes son obligatorios.");
        }
    }

    private static void requireTraceabilitySupported(Product product) {
        if (product == null) {
            throw conflict("PACKING_PRODUCT_NOT_FOUND", "Producto de Packing no encontrado.");
        }
        if (Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial())
                || Boolean.TRUE.equals(product.getTrackingExpiration())) {
            throw conflict(
                    "TRACEABILITY_NOT_SUPPORTED",
                    "Packing de productos con lote, serie o vencimiento aún no está soportado.");
        }
    }

    private String customerName(UUID tenantId, Order order) {
        if (order.getCustomerId() != null) {
            return customerRepository.findByTenantIdAndId(tenantId, order.getCustomerId())
                    .map(Customer::getName)
                    .orElse("Cliente");
        }
        if (order.getGuestCustomer() == null) return "Cliente invitado";
        Map<String, Object> guest = jsonMapper.readValue(
                order.getGuestCustomer(), new TypeReference<Map<String, Object>>() {});
        Object name = guest.get("name");
        return name instanceof String value && !value.isBlank() ? value : "Cliente invitado";
    }

    private JsonNode json(String value) {
        return value == null ? null : jsonMapper.readTree(value);
    }

    private static void clearLabel(Packing packing) {
        packing.setLabelGenerationId(null);
        packing.setLabelCode(null);
        packing.setLabelGeneratedAt(null);
        packing.setLabelPrintedAt(null);
    }

    private static boolean equalDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static String fingerprint(
            UUID packingId, PackingOperationType type, UUID actorUserId, String payload) {
        String value = packingId + "|" + type + "|" + actorUserId + "|" + payload;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }

    private record MutationContext(
            Order order,
            InventoryTransfer transfer,
            PickingOrder picking,
            String sourceReference) {}
}
