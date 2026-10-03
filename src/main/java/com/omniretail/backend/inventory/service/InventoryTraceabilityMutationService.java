package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.inventory.dto.InventoryInboundCommand;
import com.omniretail.backend.inventory.dto.InventoryInboundTraceDetail;
import com.omniretail.backend.inventory.dto.InventoryOutboundCommand;
import com.omniretail.backend.inventory.dto.InventoryRestoreCommand;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementTrace;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryLotBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryLotRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementTraceRepository;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryTraceabilityMutationService {

    private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("America/Guatemala");

    private final TenantRepository tenantRepository;
    private final LocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryLotRepository lotRepository;
    private final InventoryLotBalanceRepository lotBalanceRepository;
    private final InventorySerialRepository serialRepository;
    private final InventoryMovementRepository movementRepository;
    private final InventoryMovementTraceRepository movementTraceRepository;

    public List<InventoryInboundTraceDetail> validateAndNormalize(
            UUID tenantId,
            Product product,
            BigDecimal baseQuantity,
            List<InventoryInboundTraceDetail> requestedDetails) {
        requirePositiveQuantity(baseQuantity);
        List<InventoryInboundTraceDetail> raw = requestedDetails == null
                ? List.of()
                : requestedDetails;
        boolean stockProduct = product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock());
        boolean trackingLot = stockProduct && Boolean.TRUE.equals(product.getTrackingLot());
        boolean trackingExpiration = stockProduct
                && Boolean.TRUE.equals(product.getTrackingExpiration());
        boolean trackingSerial = stockProduct && Boolean.TRUE.equals(product.getTrackingSerial());

        if (trackingExpiration && !trackingLot) {
            throw invalidTracking("El producto no tiene una configuracion valida de trazabilidad.");
        }
        if (!stockProduct || (!trackingLot && !trackingSerial)) {
            if (!raw.isEmpty()) {
                throw invalidTracking("El producto no utiliza trazabilidad de lote o serie.");
            }
            return List.of();
        }
        if (raw.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_DETAILS_REQUIRED",
                    "El detalle de trazabilidad es requerido para el producto.");
        }

        Set<String> lotNumbers = new HashSet<>();
        Set<String> allSerials = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        List<InventoryInboundTraceDetail> normalized = new ArrayList<>(raw.size());
        for (InventoryInboundTraceDetail detail : raw) {
            if (detail == null) {
                throw invalidTracking("El detalle de trazabilidad no puede ser nulo.");
            }
            requirePositiveQuantity(detail.baseQuantity());
            String lotNumber = trimToNull(detail.lotNumber());
            LocalDate expirationDate = detail.expirationDate();
            List<String> serialNumbers = normalizeSerials(detail.serialNumbers());

            if (trackingLot) {
                if (lotNumber == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "LOT_NUMBER_REQUIRED",
                            "El numero de lote es requerido.");
                }
                if (!lotNumbers.add(lotNumber)) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "DUPLICATE_LOT_DETAIL",
                            "Un numero de lote no puede repetirse en la misma linea.");
                }
            } else if (lotNumber != null || expirationDate != null) {
                throw invalidTracking("El producto no utiliza trazabilidad por lote.");
            }

            if (trackingExpiration) {
                if (expirationDate == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "LOT_EXPIRATION_REQUIRED",
                            "La fecha de vencimiento del lote es requerida.");
                }
                if (expirationDate.isBefore(businessDate(tenantId))) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "LOT_EXPIRATION_INVALID",
                            "La fecha de vencimiento no puede ser anterior a la fecha de operacion.");
                }
            } else if (expirationDate != null) {
                throw invalidTracking("El producto no utiliza trazabilidad de vencimiento.");
            }

            if (trackingSerial) {
                if (serialNumbers.isEmpty()) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "SERIALS_REQUIRED",
                            "Los numeros de serie son requeridos.");
                }
                if (!isInteger(detail.baseQuantity())
                        || detail.baseQuantity().compareTo(BigDecimal.valueOf(serialNumbers.size())) != 0) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "SERIAL_COUNT_MISMATCH",
                            "La cantidad base debe ser entera y coincidir con el numero de series.");
                }
                for (String serial : serialNumbers) {
                    if (!allSerials.add(serial)) {
                        throw duplicateSerial();
                    }
                }
            } else if (!serialNumbers.isEmpty()) {
                throw invalidTracking("El producto no utiliza trazabilidad por serie.");
            }

            total = total.add(detail.baseQuantity());
            normalized.add(new InventoryInboundTraceDetail(
                    detail.baseQuantity(), lotNumber, expirationDate, serialNumbers));
        }
        if (total.compareTo(baseQuantity) != 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La suma de cantidades trazables debe coincidir con la cantidad base.");
        }
        return normalized.stream()
                .sorted(Comparator.comparing(
                                InventoryInboundTraceDetail::lotNumber,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(detail -> detail.serialNumbers().isEmpty()
                                ? ""
                                : detail.serialNumbers().getFirst()))
                .toList();
    }

    @Transactional
    public InventoryMovement receive(InventoryInboundCommand command) {
        Product product = command.product();
        if (product == null
                || !Objects.equals(product.getTenantId(), command.tenantId())
                || product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())) {
            throw invalidTracking("Solo un producto fisico con control de inventario puede recibirse.");
        }
        requireLocation(command.tenantId(), command.branchId(), command.locationId());
        List<InventoryInboundTraceDetail> details = validateAndNormalize(
                command.tenantId(), product, command.baseQuantity(), command.trackingDetails());

        if (command.locationId() == null) {
            balanceRepository.ensureDefaultLocationBalanceExists(
                    command.tenantId(), command.branchId(), product.getId());
        } else {
            balanceRepository.ensureLocationBalanceExists(
                    command.tenantId(), command.branchId(), product.getId(), command.locationId());
        }
        InventoryBalance balance = (command.locationId() == null
                        ? balanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                                command.tenantId(), command.branchId(), product.getId())
                        : balanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationId(
                                command.tenantId(), command.branchId(), product.getId(), command.locationId()))
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el balance de inventario de la ubicacion."));

        List<ResolvedDetail> resolvedDetails = new ArrayList<>(details.size());
        for (InventoryInboundTraceDetail detail : details) {
            InventoryLot lot = resolveLot(command.tenantId(), product, detail);
            if (lot != null) {
                InventoryLotBalance lotBalance = lockLotBalance(command, product, lot);
                lotBalance.add(detail.baseQuantity());
            }
            resolvedDetails.add(new ResolvedDetail(detail, lot));
        }

        List<InventorySerial> serials = createSerials(command, resolvedDetails);
        BigDecimal quantityBefore = balance.getQuantity();
        balance.add(command.baseQuantity());

        InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                .tenantId(command.tenantId())
                .branchId(command.branchId())
                .productId(product.getId())
                .type(InventoryMovementType.in)
                .reason(command.reason())
                .quantity(command.baseQuantity())
                .quantityBefore(quantityBefore)
                .quantityAfter(balance.getQuantity())
                .toLocationId(command.locationId())
                .referenceType(command.referenceType())
                .referenceId(command.referenceId())
                .referenceLineId(command.referenceLineId())
                .performedByUserId(command.actorUserId())
                .build());
        saveTraces(command.tenantId(), movement.getId(), resolvedDetails, serials);
        return movement;
    }

    @Transactional
    public InventoryMovement consume(InventoryOutboundCommand command) {
        Product product = requireTraceableProduct(command.tenantId(), command.product());
        if (command.locationId() == null) {
            throw invalidTracking("La ubicacion de salida del inventario trazable es requerida.");
        }
        requireLocation(command.tenantId(), command.branchId(), command.locationId());
        List<NormalizedSelection> selections = normalizeOperationalSelections(
                command.baseQuantity(), product, command.selections());
        LockedOperationalStock stock = lockOperationalStock(
                command.tenantId(), command.branchId(), command.locationId(), product, selections, true);
        List<InventorySerial> serials = lockAndValidateSerials(
                command.tenantId(), command.branchId(), command.locationId(), product,
                selections, InventorySerialStatus.AVAILABLE);

        BigDecimal quantityBefore = stock.balance().getQuantity();
        try {
            stock.balance().deduct(command.baseQuantity());
            stock.lotBalances().forEach((lotId, balance) ->
                    balance.deduct(quantityForLot(selections, lotId)));
        } catch (IllegalStateException exception) {
            throw insufficientTraceableStock();
        }
        serials.forEach(serial -> serial.setStatus(InventorySerialStatus.CONSUMED));

        InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                .tenantId(command.tenantId())
                .branchId(command.branchId())
                .productId(product.getId())
                .type(InventoryMovementType.out)
                .reason(command.reason())
                .quantity(command.baseQuantity())
                .quantityBefore(quantityBefore)
                .quantityAfter(stock.balance().getQuantity())
                .fromLocationId(command.locationId())
                .referenceType(command.referenceType())
                .referenceId(command.referenceId())
                .referenceLineId(command.referenceLineId())
                .performedByUserId(command.actorUserId())
                .build());
        saveOperationalTraces(command.tenantId(), movement.getId(), selections, serials);
        return movement;
    }

    @Transactional
    public InventoryMovement restore(InventoryRestoreCommand command) {
        Product product = requireTraceableProduct(command.tenantId(), command.product());
        requireHistoricalLocation(command.tenantId(), command.branchId(), command.locationId());
        List<NormalizedSelection> selections = normalizeOperationalSelections(
                command.baseQuantity(), product, command.selections());
        LockedOperationalStock stock = lockOperationalStock(
                command.tenantId(), command.branchId(), command.locationId(), product, selections, false);
        List<InventorySerial> serials = lockAndValidateSerials(
                command.tenantId(), command.branchId(), command.locationId(), product,
                selections, InventorySerialStatus.CONSUMED);

        BigDecimal quantityBefore = stock.balance().getQuantity();
        stock.balance().add(command.baseQuantity());
        stock.lotBalances().forEach((lotId, balance) ->
                balance.add(quantityForLot(selections, lotId)));
        serials.forEach(serial -> serial.setStatus(InventorySerialStatus.AVAILABLE));

        InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                .tenantId(command.tenantId())
                .branchId(command.branchId())
                .productId(product.getId())
                .type(InventoryMovementType.in)
                .reason(command.reason())
                .quantity(command.baseQuantity())
                .quantityBefore(quantityBefore)
                .quantityAfter(stock.balance().getQuantity())
                .toLocationId(command.locationId())
                .referenceType(command.referenceType())
                .referenceId(command.referenceId())
                .referenceLineId(command.referenceLineId())
                .performedByUserId(command.actorUserId())
                .build());
        saveOperationalTraces(command.tenantId(), movement.getId(), selections, serials);
        return movement;
    }

    @Transactional
    public List<InventoryTraceabilitySelection> replacePhysicalReservation(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            BigDecimal targetQuantity,
            List<InventoryTraceabilitySelection> oldSelections,
            List<InventoryTraceabilitySelection> newSelections) {
        Product traceableProduct = requireTraceableProduct(tenantId, product);
        if (locationId == null) {
            throw invalidTracking("La ubicacion del Picking trazable es requerida.");
        }
        if (targetQuantity != null && targetQuantity.signum() == 0
                && (newSelections == null || newSelections.isEmpty())) {
            requireHistoricalLocation(tenantId, branchId, locationId);
        } else {
            requireLocation(tenantId, branchId, locationId);
        }
        List<NormalizedSelection> oldNormalized = normalizeExistingSelections(
                traceableProduct, oldSelections);
        List<NormalizedSelection> newNormalized = targetQuantity == null
                        || targetQuantity.signum() == 0
                ? normalizeEmptyTarget(targetQuantity, newSelections)
                : normalizeOperationalSelections(targetQuantity, traceableProduct, newSelections);

        Map<UUID, BigDecimal> oldLots = quantitiesByLot(oldNormalized);
        Map<UUID, BigDecimal> newLots = quantitiesByLot(newNormalized);
        List<UUID> lotIds = java.util.stream.Stream.concat(
                        oldLots.keySet().stream(), newLots.keySet().stream())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        for (UUID lotId : lotIds) {
            InventoryLot lot = lotRepository.findByTenantIdAndId(tenantId, lotId)
                    .filter(value -> value.getProductId().equals(traceableProduct.getId()))
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Lote no encontrado."));
            BigDecimal newQuantity = newLots.getOrDefault(lotId, BigDecimal.ZERO);
            if (newQuantity.signum() > 0
                    && Boolean.TRUE.equals(traceableProduct.getTrackingExpiration())
                    && (lot.getExpirationDate() == null
                            || lot.getExpirationDate().isBefore(businessDate(tenantId)))) {
                throw BusinessException.conflict(
                        "LOT_EXPIRED", "El lote seleccionado esta vencido.");
            }
            InventoryLotBalance balance = lotBalanceRepository.findForUpdateAtLocation(
                            tenantId, branchId, traceableProduct.getId(), lotId, locationId)
                    .orElseThrow(InventoryTraceabilityMutationService::insufficientTraceableStock);
            BigDecimal oldQuantity = oldLots.getOrDefault(lotId, BigDecimal.ZERO);
            BigDecimal delta = newQuantity.subtract(oldQuantity);
            try {
                if (delta.signum() > 0) balance.reserve(delta);
                if (delta.signum() < 0) balance.releaseReservation(delta.negate());
                if (delta.signum() == 0 && newQuantity.signum() > 0
                        && balance.getReservedQuantity().compareTo(newQuantity) < 0) {
                    throw new IllegalStateException("Reserva fisica inconsistente.");
                }
            } catch (IllegalStateException exception) {
                throw insufficientTraceableStock();
            }
        }
        replaceReservedSerials(
                tenantId, branchId, locationId, traceableProduct, oldNormalized, newNormalized);
        return newNormalized.stream()
                .map(selection -> new InventoryTraceabilitySelection(
                        selection.lotId(), selection.quantity(), selection.serialNumbers()))
                .toList();
    }

    @Transactional
    public void validatePhysicalReservation(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections) {
        replacePhysicalReservation(
                tenantId, branchId, product, locationId, quantity, selections, selections);
    }

    @Transactional
    public void releasePhysicalReservation(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            List<InventoryTraceabilitySelection> selections) {
        replacePhysicalReservation(
                tenantId, branchId, product, locationId, BigDecimal.ZERO, selections, List.of());
    }

    @Transactional
    public InventoryMovement consumePhysicalReservation(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections,
            InventorySerialStatus serialTargetStatus,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            String reason,
            String referenceType,
            UUID referenceId,
            UUID referenceLineId,
            UUID actorUserId) {
        Product traceableProduct = requireTraceableProduct(tenantId, product);
        requireHistoricalLocation(tenantId, branchId, locationId);
        List<NormalizedSelection> normalized = normalizeOperationalSelections(
                quantity, traceableProduct, selections);
        if (serialTargetStatus != InventorySerialStatus.CONSUMED
                && serialTargetStatus != InventorySerialStatus.IN_TRANSIT) {
            throw invalidTracking("El estado destino del serial no es valido para despacho.");
        }
        Map<UUID, BigDecimal> lots = quantitiesByLot(normalized);
        for (UUID lotId : lots.keySet().stream().filter(Objects::nonNull).sorted().toList()) {
            InventoryLot lot = lotRepository.findByTenantIdAndId(tenantId, lotId)
                    .filter(value -> value.getProductId().equals(traceableProduct.getId()))
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Lote no encontrado."));
            InventoryLotBalance balance = lotBalanceRepository.findForUpdateAtLocation(
                            tenantId, branchId, traceableProduct.getId(), lot.getId(), locationId)
                    .orElseThrow(InventoryTraceabilityMutationService::insufficientTraceableStock);
            try {
                balance.consumeReservation(lots.get(lotId));
            } catch (IllegalStateException exception) {
                throw insufficientTraceableStock();
            }
        }
        List<InventorySerial> serials = lockAndValidateSerials(
                tenantId, branchId, locationId, traceableProduct,
                normalized, InventorySerialStatus.RESERVED);
        serials.forEach(serial -> serial.setStatus(serialTargetStatus));
        InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .productId(traceableProduct.getId())
                .type(InventoryMovementType.out)
                .reason(reason)
                .quantity(quantity)
                .quantityBefore(quantityBefore)
                .quantityAfter(quantityAfter)
                .fromLocationId(locationId)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .referenceLineId(referenceLineId)
                .performedByUserId(actorUserId)
                .build());
        saveOperationalTraces(tenantId, movement.getId(), normalized, serials);
        return movement;
    }

    @Transactional
    public void receiveTransferredPhysicalStock(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            BigDecimal quantity,
            List<InventoryTraceabilitySelection> selections,
            UUID movementId) {
        Product traceableProduct = requireTraceableProduct(tenantId, product);
        requireLocation(tenantId, branchId, locationId);
        List<NormalizedSelection> normalized = normalizeOperationalSelections(
                quantity, traceableProduct, selections);
        Map<UUID, BigDecimal> lots = quantitiesByLot(normalized);
        for (UUID lotId : lots.keySet().stream().filter(Objects::nonNull).sorted().toList()) {
            InventoryLot lot = lotRepository.findByTenantIdAndId(tenantId, lotId)
                    .filter(value -> value.getProductId().equals(traceableProduct.getId()))
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Lote no encontrado."));
            lotBalanceRepository.ensureAtLocationExists(
                    UUID.randomUUID(), tenantId, branchId, locationId, lot.getId());
            InventoryLotBalance balance = lotBalanceRepository.findForUpdateAtLocation(
                            tenantId, branchId, traceableProduct.getId(), lot.getId(), locationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No se pudo inicializar el balance trazable del lote destino."));
            balance.add(lots.get(lotId));
        }

        List<InventorySerial> serials = lockTransferredSerials(
                tenantId, traceableProduct, normalized);
        serials.forEach(serial -> {
            serial.setBranchId(branchId);
            serial.setLocationId(locationId);
            serial.setStatus(InventorySerialStatus.AVAILABLE);
        });
        saveOperationalTraces(tenantId, movementId, normalized, serials);
    }

    private Product requireTraceableProduct(UUID tenantId, Product product) {
        if (product == null
                || !Objects.equals(product.getTenantId(), tenantId)
                || product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())
                || (!Boolean.TRUE.equals(product.getTrackingLot())
                        && !Boolean.TRUE.equals(product.getTrackingSerial()))) {
            throw invalidTracking("El producto no utiliza inventario trazable operacional.");
        }
        return product;
    }

    private List<NormalizedSelection> normalizeOperationalSelections(
            BigDecimal baseQuantity,
            Product product,
            List<InventoryTraceabilitySelection> requestedSelections) {
        requirePositiveQuantity(baseQuantity);
        List<InventoryTraceabilitySelection> raw = requestedSelections == null
                ? List.of()
                : requestedSelections;
        if (raw.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_SELECTIONS_REQUIRED",
                    "La seleccion explicita de inventario trazable es requerida.");
        }
        boolean trackingLot = Boolean.TRUE.equals(product.getTrackingLot());
        boolean trackingSerial = Boolean.TRUE.equals(product.getTrackingSerial());
        boolean trackingExpiration = Boolean.TRUE.equals(product.getTrackingExpiration());
        if (trackingExpiration && !trackingLot) {
            throw invalidTracking("El producto no tiene una configuracion valida de trazabilidad.");
        }

        BigDecimal total = BigDecimal.ZERO;
        Set<UUID> lots = new HashSet<>();
        Set<String> serials = new HashSet<>();
        List<NormalizedSelection> normalized = new ArrayList<>(raw.size());
        for (InventoryTraceabilitySelection selection : raw) {
            if (selection == null) throw invalidTracking("La seleccion trazable no puede ser nula.");
            requirePositiveQuantity(selection.quantity());
            UUID lotId = selection.lotId();
            List<String> numbers = normalizeSerials(selection.serialNumbers());
            if (trackingLot) {
                if (lotId == null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST, "LOT_REQUIRED", "El lote es requerido.");
                }
                if (!lots.add(lotId)) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "DUPLICATE_LOT_SELECTION",
                            "Un lote no puede repetirse para el mismo producto y ubicacion.");
                }
            } else if (lotId != null) {
                throw invalidTracking("El producto no utiliza trazabilidad por lote.");
            }
            if (trackingSerial) {
                if (numbers.isEmpty()) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST, "SERIALS_REQUIRED", "Los numeros de serie son requeridos.");
                }
                if (!isInteger(selection.quantity())
                        || selection.quantity().compareTo(BigDecimal.valueOf(numbers.size())) != 0) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "SERIAL_COUNT_MISMATCH",
                            "La cantidad debe ser entera y coincidir con el numero de series.");
                }
                for (String number : numbers) {
                    if (!serials.add(number)) throw duplicateSerial();
                }
            } else if (!numbers.isEmpty()) {
                throw invalidTracking("El producto no utiliza trazabilidad por serie.");
            }
            total = total.add(selection.quantity());
            normalized.add(new NormalizedSelection(lotId, selection.quantity(), numbers));
        }
        if (total.compareTo(baseQuantity) != 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La seleccion trazable debe coincidir con la cantidad base requerida.");
        }
        return normalized.stream()
                .sorted(Comparator.comparing(
                                NormalizedSelection::lotId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(selection -> selection.serialNumbers().isEmpty()
                                ? ""
                                : selection.serialNumbers().getFirst()))
                .toList();
    }

    private LockedOperationalStock lockOperationalStock(
            UUID tenantId,
            UUID branchId,
            UUID locationId,
            Product product,
            List<NormalizedSelection> selections,
            boolean rejectExpired) {
        InventoryBalance balance = balanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationId(
                        tenantId, branchId, product.getId(), locationId)
                .orElseThrow(InventoryTraceabilityMutationService::insufficientTraceableStock);
        BigDecimal selected = selections.stream()
                .map(NormalizedSelection::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rejectExpired
                && selected.compareTo(balance.getQuantity().subtract(balance.getReservedQuantity())) > 0) {
            throw insufficientTraceableStock();
        }

        Map<UUID, InventoryLotBalance> lotBalances = new HashMap<>();
        for (NormalizedSelection selection : selections) {
            if (selection.lotId() == null) continue;
            InventoryLot lot = lotRepository.findByTenantIdAndId(tenantId, selection.lotId())
                    .filter(value -> value.getProductId().equals(product.getId()))
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Lote no encontrado."));
            if (rejectExpired
                    && Boolean.TRUE.equals(product.getTrackingExpiration())
                    && (lot.getExpirationDate() == null
                            || lot.getExpirationDate().isBefore(businessDate(tenantId)))) {
                throw BusinessException.conflict(
                        "LOT_EXPIRED", "El lote seleccionado esta vencido.");
            }
            InventoryLotBalance lotBalance = lotBalanceRepository.findForUpdateAtLocation(
                            tenantId, branchId, product.getId(), lot.getId(), locationId)
                    .orElseThrow(InventoryTraceabilityMutationService::insufficientTraceableStock);
            if (rejectExpired
                    && selection.quantity().compareTo(
                                    lotBalance.getQuantity().subtract(lotBalance.getReservedQuantity()))
                            > 0) {
                throw insufficientTraceableStock();
            }
            lotBalances.put(lot.getId(), lotBalance);
        }
        return new LockedOperationalStock(balance, Map.copyOf(lotBalances));
    }

    private List<InventorySerial> lockAndValidateSerials(
            UUID tenantId,
            UUID branchId,
            UUID locationId,
            Product product,
            List<NormalizedSelection> selections,
            InventorySerialStatus expectedStatus) {
        if (!Boolean.TRUE.equals(product.getTrackingSerial())) return List.of();
        Map<String, UUID> expectedLots = new HashMap<>();
        selections.forEach(selection -> selection.serialNumbers().forEach(number ->
                expectedLots.put(number, selection.lotId())));
        List<String> numbers = expectedLots.keySet().stream().sorted().toList();
        List<InventorySerial> serials = serialRepository
                .findAllForUpdateByTenantProductAndSerialNumberIn(tenantId, product.getId(), numbers);
        if (serials.size() != numbers.size()) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "SERIAL_NOT_FOUND", "Uno o mas numeros de serie no existen.");
        }
        for (InventorySerial serial : serials) {
            if (serial.getStatus() != expectedStatus) {
                throw BusinessException.conflict(
                        "SERIAL_STATUS_CONFLICT",
                        "Uno o mas numeros de serie no estan en el estado requerido.");
            }
            if (!serial.getBranchId().equals(branchId)
                    || !Objects.equals(serial.getLocationId(), locationId)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOCATION_MISMATCH",
                        "Uno o mas numeros de serie no pertenecen a la sucursal y ubicacion indicadas.");
            }
            if (!Objects.equals(serial.getLotId(), expectedLots.get(serial.getSerialNumber()))) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOT_MISMATCH",
                        "Uno o mas numeros de serie no pertenecen al lote indicado.");
            }
        }
        return serials;
    }

    private void saveOperationalTraces(
            UUID tenantId,
            UUID movementId,
            List<NormalizedSelection> selections,
            List<InventorySerial> serials) {
        List<InventoryMovementTrace> traces = !serials.isEmpty()
                ? serials.stream()
                        .map(serial -> InventoryMovementTrace.builder()
                                .tenantId(tenantId)
                                .movementId(movementId)
                                .serialId(serial.getId())
                                .quantity(BigDecimal.ONE)
                                .build())
                        .toList()
                : selections.stream()
                        .map(selection -> InventoryMovementTrace.builder()
                                .tenantId(tenantId)
                                .movementId(movementId)
                                .lotId(selection.lotId())
                                .quantity(selection.quantity())
                                .build())
                        .toList();
        movementTraceRepository.saveAllAndFlush(traces);
    }

    private static BigDecimal quantityForLot(
            List<NormalizedSelection> selections, UUID lotId) {
        return selections.stream()
                .filter(selection -> Objects.equals(selection.lotId(), lotId))
                .map(NormalizedSelection::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<NormalizedSelection> normalizeExistingSelections(
            Product product, List<InventoryTraceabilitySelection> selections) {
        if (selections == null || selections.isEmpty()) return List.of();
        BigDecimal quantity = selections.stream()
                .map(InventoryTraceabilitySelection::quantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return normalizeOperationalSelections(quantity, product, selections);
    }

    private static List<NormalizedSelection> normalizeEmptyTarget(
            BigDecimal targetQuantity, List<InventoryTraceabilitySelection> selections) {
        if (targetQuantity == null || targetQuantity.signum() != 0
                || (selections != null && !selections.isEmpty())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "TRACKING_QUANTITY_MISMATCH",
                    "La seleccion trazable debe coincidir con la cantidad requerida.");
        }
        return List.of();
    }

    private static Map<UUID, BigDecimal> quantitiesByLot(
            List<NormalizedSelection> selections) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        selections.forEach(selection -> {
            if (selection.lotId() != null) {
                result.merge(selection.lotId(), selection.quantity(), BigDecimal::add);
            }
        });
        return result;
    }

    private void replaceReservedSerials(
            UUID tenantId,
            UUID branchId,
            UUID locationId,
            Product product,
            List<NormalizedSelection> oldSelections,
            List<NormalizedSelection> newSelections) {
        if (!Boolean.TRUE.equals(product.getTrackingSerial())) return;
        Map<String, UUID> oldSerials = serialLots(oldSelections);
        Map<String, UUID> newSerials = serialLots(newSelections);
        List<String> union = java.util.stream.Stream.concat(
                        oldSerials.keySet().stream(), newSerials.keySet().stream())
                .distinct()
                .sorted()
                .toList();
        if (union.isEmpty()) return;
        List<InventorySerial> serials = serialRepository
                .findAllForUpdateByTenantProductAndSerialNumberIn(
                        tenantId, product.getId(), union);
        if (serials.size() != union.size()) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "SERIAL_NOT_FOUND", "Uno o mas numeros de serie no existen.");
        }
        for (InventorySerial serial : serials) {
            String number = serial.getSerialNumber();
            boolean wasSelected = oldSerials.containsKey(number);
            boolean isSelected = newSerials.containsKey(number);
            UUID expectedLot = isSelected ? newSerials.get(number) : oldSerials.get(number);
            if (!serial.getBranchId().equals(branchId)
                    || !Objects.equals(serial.getLocationId(), locationId)
                    || !Objects.equals(serial.getLotId(), expectedLot)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOCATION_MISMATCH",
                        "Uno o mas seriales no coinciden con la ubicacion y lote seleccionados.");
            }
            if (wasSelected && isSelected) {
                if (serial.getStatus() != InventorySerialStatus.RESERVED
                        || !Objects.equals(oldSerials.get(number), newSerials.get(number))) {
                    throw serialReservationConflict();
                }
            } else if (wasSelected) {
                if (serial.getStatus() != InventorySerialStatus.RESERVED) {
                    throw serialReservationConflict();
                }
                serial.setStatus(InventorySerialStatus.AVAILABLE);
            } else {
                if (serial.getStatus() != InventorySerialStatus.AVAILABLE) {
                    throw serialReservationConflict();
                }
                serial.setStatus(InventorySerialStatus.RESERVED);
            }
        }
    }

    private List<InventorySerial> lockTransferredSerials(
            UUID tenantId,
            Product product,
            List<NormalizedSelection> selections) {
        if (!Boolean.TRUE.equals(product.getTrackingSerial())) return List.of();
        Map<String, UUID> expectedLots = serialLots(selections);
        List<String> numbers = expectedLots.keySet().stream().sorted().toList();
        List<InventorySerial> serials = serialRepository
                .findAllForUpdateByTenantProductAndSerialNumberIn(
                        tenantId, product.getId(), numbers);
        if (serials.size() != numbers.size()) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "SERIAL_NOT_FOUND", "Uno o mas numeros de serie no existen.");
        }
        for (InventorySerial serial : serials) {
            if (serial.getStatus() != InventorySerialStatus.IN_TRANSIT) {
                throw BusinessException.conflict(
                        "SERIAL_STATUS_CONFLICT",
                        "Uno o mas seriales no se encuentran en transito.");
            }
            if (!Objects.equals(serial.getLotId(), expectedLots.get(serial.getSerialNumber()))) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOT_MISMATCH",
                        "Uno o mas seriales no pertenecen al lote despachado.");
            }
        }
        return serials;
    }

    private static Map<String, UUID> serialLots(List<NormalizedSelection> selections) {
        Map<String, UUID> result = new HashMap<>();
        selections.forEach(selection -> selection.serialNumbers().forEach(number ->
                result.put(number, selection.lotId())));
        return result;
    }

    private static BusinessException serialReservationConflict() {
        return BusinessException.conflict(
                "SERIAL_RESERVATION_CONFLICT",
                "Uno o mas seriales ya estan reservados o no pertenecen a esta seleccion.");
    }

    private InventoryLot resolveLot(
            UUID tenantId, Product product, InventoryInboundTraceDetail detail) {
        if (!Boolean.TRUE.equals(product.getTrackingLot())) return null;
        lotRepository.ensureExists(
                UUID.randomUUID(),
                tenantId,
                product.getId(),
                detail.lotNumber(),
                detail.expirationDate());
        InventoryLot lot = lotRepository
                .findByTenantIdAndProductIdAndLotNumber(
                        tenantId, product.getId(), detail.lotNumber())
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el lote."));
        if (Boolean.TRUE.equals(product.getTrackingExpiration())
                && !Objects.equals(lot.getExpirationDate(), detail.expirationDate())) {
            throw BusinessException.conflict(
                    "LOT_EXPIRATION_MISMATCH",
                    "La fecha de vencimiento no coincide con la registrada para el lote.");
        }
        return lot;
    }

    private InventoryLotBalance lockLotBalance(
            InventoryInboundCommand command, Product product, InventoryLot lot) {
        if (command.locationId() == null) {
            lotBalanceRepository.ensureWithoutLocationExists(
                    UUID.randomUUID(), command.tenantId(), command.branchId(), lot.getId());
        } else {
            lotBalanceRepository.ensureAtLocationExists(
                    UUID.randomUUID(),
                    command.tenantId(),
                    command.branchId(),
                    command.locationId(),
                    lot.getId());
        }
        return (command.locationId() == null
                        ? lotBalanceRepository.findForUpdateWithoutLocation(
                                command.tenantId(),
                                command.branchId(),
                                product.getId(),
                                lot.getId())
                        : lotBalanceRepository.findForUpdateAtLocation(
                                command.tenantId(),
                                command.branchId(),
                                product.getId(),
                                lot.getId(),
                                command.locationId()))
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el balance trazable del lote."));
    }

    private List<InventorySerial> createSerials(
            InventoryInboundCommand command, List<ResolvedDetail> details) {
        if (!Boolean.TRUE.equals(command.product().getTrackingSerial())) return List.of();
        List<String> numbers = details.stream()
                .flatMap(detail -> detail.detail().serialNumbers().stream())
                .sorted()
                .toList();
        if (!serialRepository.findByTenantIdAndProductIdAndSerialNumberIn(
                        command.tenantId(), command.product().getId(), numbers)
                .isEmpty()) {
            throw duplicateSerial();
        }
        List<InventorySerial> serials = details.stream()
                .flatMap(resolved -> resolved.detail().serialNumbers().stream().map(number -> {
                    InventorySerial serial = InventorySerial.builder()
                            .branchId(command.branchId())
                            .locationId(command.locationId())
                            .productId(command.product().getId())
                            .serialNumber(number)
                            .lotId(resolved.lot() == null ? null : resolved.lot().getId())
                            .status(InventorySerialStatus.AVAILABLE)
                            .build();
                    serial.setTenantId(command.tenantId());
                    return serial;
                }))
                .sorted(Comparator.comparing(InventorySerial::getSerialNumber))
                .toList();
        try {
            return serialRepository.saveAllAndFlush(serials);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateSerial();
        }
    }

    private void saveTraces(
            UUID tenantId,
            UUID movementId,
            List<ResolvedDetail> details,
            List<InventorySerial> serials) {
        List<InventoryMovementTrace> traces;
        if (!serials.isEmpty()) {
            traces = serials.stream()
                    .map(serial -> InventoryMovementTrace.builder()
                            .tenantId(tenantId)
                            .movementId(movementId)
                            .serialId(serial.getId())
                            .quantity(BigDecimal.ONE)
                            .build())
                    .toList();
        } else {
            traces = details.stream()
                    .filter(detail -> detail.lot() != null)
                    .map(detail -> InventoryMovementTrace.builder()
                            .tenantId(tenantId)
                            .movementId(movementId)
                            .lotId(detail.lot().getId())
                            .quantity(detail.detail().baseQuantity())
                            .build())
                    .toList();
        }
        if (!traces.isEmpty()) movementTraceRepository.saveAllAndFlush(traces);
    }

    private void requireLocation(UUID tenantId, UUID branchId, UUID locationId) {
        if (locationId == null) return;
        Location location = locationRepository.findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "Ubicacion no encontrada."));
        if (!location.getBranchId().equals(branchId)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_BRANCH_MISMATCH",
                    "La ubicacion no pertenece a la sucursal indicada.");
        }
        if (location.getStatus() != LocationStatus.active) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_NOT_ACTIVE",
                    "La ubicacion debe estar activa para recibir inventario.");
        }
    }

    private void requireHistoricalLocation(UUID tenantId, UUID branchId, UUID locationId) {
        if (locationId == null) {
            throw invalidTracking("La ubicacion historica del inventario trazable es requerida.");
        }
        Location location = locationRepository.findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "Ubicacion no encontrada."));
        if (!location.getBranchId().equals(branchId)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_BRANCH_MISMATCH",
                    "La ubicacion no pertenece a la sucursal indicada.");
        }
    }

    private LocalDate businessDate(UUID tenantId) {
        ZoneId zone = tenantRepository.findById(tenantId)
                .map(Tenant::getTimezone)
                .map(InventoryTraceabilityMutationService::zoneId)
                .orElse(DEFAULT_BUSINESS_ZONE);
        return LocalDate.now(zone);
    }

    private static ZoneId zoneId(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            return DEFAULT_BUSINESS_ZONE;
        }
    }

    private static List<String> normalizeSerials(List<String> values) {
        if (values == null) return List.of();
        List<String> normalized = values.stream()
                .map(InventoryTraceabilityMutationService::trimToNull)
                .toList();
        if (normalized.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "SERIAL_INVALID",
                    "Los numeros de serie no pueden estar vacios.");
        }
        Set<String> unique = new HashSet<>(normalized);
        if (unique.size() != normalized.size()) throw duplicateSerial();
        return normalized.stream().sorted().toList();
    }

    private static void requirePositiveQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0 || !fitsDecimal(quantity, 12, 3)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_QUANTITY",
                    "La cantidad debe ser positiva y admitir hasta tres decimales.");
        }
    }

    private static boolean fitsDecimal(BigDecimal value, int precision, int scale) {
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        return fractionDigits <= scale && integerDigits <= precision - scale;
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException invalidTracking(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRACKING_PAYLOAD", message);
    }

    private static BusinessException duplicateSerial() {
        return BusinessException.conflict(
                "DUPLICATE_SERIAL", "Uno o mas numeros de serie ya existen o estan repetidos.");
    }

    private static BusinessException insufficientTraceableStock() {
        return BusinessException.conflict(
                "INSUFFICIENT_TRACEABLE_STOCK",
                "No existe inventario trazable disponible suficiente.");
    }

    private record ResolvedDetail(InventoryInboundTraceDetail detail, InventoryLot lot) {}

    private record NormalizedSelection(UUID lotId, BigDecimal quantity, List<String> serialNumbers) {}

    private record LockedOperationalStock(
            InventoryBalance balance, Map<UUID, InventoryLotBalance> lotBalances) {}
}
