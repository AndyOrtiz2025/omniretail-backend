package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentType;
import com.omniretail.backend.inventory.dto.InventoryInboundCommand;
import com.omniretail.backend.inventory.dto.InventoryInboundTraceDetail;
import com.omniretail.backend.inventory.dto.InventoryMovementResponse;
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
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryTraceabilityAdjustmentService {

    private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("America/Guatemala");

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryLotRepository lotRepository;
    private final InventoryLotBalanceRepository lotBalanceRepository;
    private final InventorySerialRepository serialRepository;
    private final InventoryMovementRepository movementRepository;
    private final InventoryMovementTraceRepository movementTraceRepository;
    private final InventoryTraceabilityMutationService mutationService;

    @Transactional
    public InventoryMovementResponse adjust(InventoryAdjustmentRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        requireBranchAccess(actor, request.branchId());
        Product product = requireProduct(tenantId, request.productId());
        requireAdjustableProduct(product);
        if (request.type() == InventoryAdjustmentType.in) {
            if (request.lotId() != null) {
                throw invalidTracking("La entrada por lote debe identificar el lote por su numero.");
            }
            InventoryMovement movement = mutationService.receive(new InventoryInboundCommand(
                    tenantId,
                    request.branchId(),
                    product,
                    request.locationId(),
                    request.quantity(),
                    inboundDetails(product, request),
                    request.reason().trim(),
                    request.referenceType(),
                    request.referenceId(),
                    null,
                    actor.userId()));
            return InventoryMovementResponse.from(movement);
        }
        Location location = requireLocation(tenantId, request.branchId(), request.locationId());
        TraceInput trace = validateTraceInput(tenantId, product, request);

        InventoryBalance balance = lockAggregateBalance(
                tenantId, request.branchId(), product.getId(), request.locationId(), request.type());
        ensureAggregateAvailable(balance, request.quantity(), request.type());

        InventoryLot lot = resolveLot(tenantId, product, request, trace);
        InventoryLotBalance lotBalance = resolveLotBalance(
                tenantId, request.branchId(), product, request.locationId(), request.type(), lot);
        ensureLotAvailable(lotBalance, request.quantity(), request.type());

        List<InventorySerial> serials = resolveSerials(
                tenantId, request.branchId(), product, location, lot, request.type(), trace);

        BigDecimal quantityBefore = balance.getQuantity();
        mutateAggregate(balance, request.quantity(), request.type());
        mutateLotBalance(lotBalance, request.quantity(), request.type());
        if (trace.trackingSerial()) {
            if (request.type() == InventoryAdjustmentType.in) {
                serials = createSerials(
                        tenantId, request.branchId(), product.getId(), request.locationId(), lot, trace.serialNumbers());
            } else {
                serials.forEach(serial -> serial.setStatus(InventorySerialStatus.WRITTEN_OFF));
            }
        }

        InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                .tenantId(tenantId)
                .branchId(request.branchId())
                .productId(product.getId())
                .type(request.type() == InventoryAdjustmentType.in
                        ? InventoryMovementType.in : InventoryMovementType.out)
                .reason(request.reason().trim())
                .quantity(request.quantity())
                .quantityBefore(quantityBefore)
                .quantityAfter(balance.getQuantity())
                .fromLocationId(request.type() == InventoryAdjustmentType.out ? request.locationId() : null)
                .toLocationId(request.type() == InventoryAdjustmentType.in ? request.locationId() : null)
                .referenceType(request.referenceType())
                .referenceId(request.referenceId())
                .performedByUserId(actor.userId())
                .build());
        saveTraces(tenantId, movement.getId(), lot, serials, trace, request.quantity());
        return InventoryMovementResponse.from(movement);
    }

    private static List<InventoryInboundTraceDetail> inboundDetails(
            Product product, InventoryAdjustmentRequest request) {
        boolean requiresDetails = Boolean.TRUE.equals(product.getTrackingLot())
                || Boolean.TRUE.equals(product.getTrackingSerial());
        boolean containsDetails = request.lotNumber() != null
                || request.expirationDate() != null
                || (request.serialNumbers() != null && !request.serialNumbers().isEmpty());
        if (!requiresDetails && !containsDetails) return List.of();
        return List.of(new InventoryInboundTraceDetail(
                request.quantity(),
                request.lotNumber(),
                request.expirationDate(),
                request.serialNumbers()));
    }

    private void requireBranchAccess(AuthenticatedUser actor, UUID branchId) {
        branchRepository.findByTenantIdAndId(actor.tenantId(), branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (!branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private static void requireAdjustableProduct(Product product) {
        if (product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVENTORY_ADJUSTMENT_PRODUCT_UNSUPPORTED",
                    "Solo pueden ajustarse productos fisicos con control de inventario.");
        }
    }

    private Location requireLocation(UUID tenantId, UUID branchId, UUID locationId) {
        if (locationId == null) return null;
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
                    "La ubicacion debe estar activa para ajustar inventario.");
        }
        return location;
    }

    private TraceInput validateTraceInput(
            UUID tenantId, Product product, InventoryAdjustmentRequest request) {
        boolean trackingLot = Boolean.TRUE.equals(product.getTrackingLot());
        boolean trackingExpiration = Boolean.TRUE.equals(product.getTrackingExpiration());
        boolean trackingSerial = Boolean.TRUE.equals(product.getTrackingSerial());
        if (trackingExpiration && !trackingLot) {
            throw invalidTracking("El producto no tiene una configuracion valida de trazabilidad.");
        }

        String lotNumber = trimToNull(request.lotNumber());
        List<String> serialNumbers = normalizeSerials(request.serialNumbers());
        if (!trackingLot && (request.lotId() != null || lotNumber != null || request.expirationDate() != null)) {
            throw invalidTracking("El producto no utiliza trazabilidad por lote.");
        }
        if (!trackingSerial && !serialNumbers.isEmpty()) {
            throw invalidTracking("El producto no utiliza trazabilidad por serie.");
        }

        if (trackingLot && request.type() == InventoryAdjustmentType.in) {
            if (lotNumber == null) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "LOT_NUMBER_REQUIRED", "El numero de lote es requerido.");
            }
            if (request.lotId() != null) {
                throw invalidTracking("La entrada por lote debe identificar el lote por su numero.");
            }
        }
        if (trackingLot && request.type() == InventoryAdjustmentType.out) {
            if (request.lotId() == null) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "LOT_REQUIRED", "El lote es requerido para la salida.");
            }
            if (lotNumber != null || request.expirationDate() != null) {
                throw invalidTracking("La salida debe utilizar la metadata del lote existente.");
            }
        }

        if (trackingExpiration && request.type() == InventoryAdjustmentType.in) {
            if (request.expirationDate() == null) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "LOT_EXPIRATION_REQUIRED",
                        "La fecha de vencimiento del lote es requerida.");
            }
            if (request.expirationDate().isBefore(businessDate(tenantId))) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "LOT_EXPIRATION_INVALID",
                        "La fecha de vencimiento no puede ser anterior a la fecha de operacion.");
            }
        } else if (!trackingExpiration && request.expirationDate() != null) {
            throw invalidTracking("El producto no utiliza trazabilidad de vencimiento.");
        }

        if (trackingSerial) {
            if (serialNumbers.isEmpty()) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "SERIALS_REQUIRED", "Los numeros de serie son requeridos.");
            }
            if (request.quantity().stripTrailingZeros().scale() > 0
                    || request.quantity().compareTo(BigDecimal.valueOf(serialNumbers.size())) != 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_COUNT_MISMATCH",
                        "La cantidad debe ser entera y coincidir con el numero de series.");
            }
            Set<String> unique = new HashSet<>(serialNumbers);
            if (unique.size() != serialNumbers.size()) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "DUPLICATE_SERIAL",
                        "Los numeros de serie no pueden repetirse.");
            }
        }
        return new TraceInput(trackingLot, trackingExpiration, trackingSerial, lotNumber, serialNumbers);
    }

    private InventoryBalance lockAggregateBalance(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID locationId,
            InventoryAdjustmentType type) {
        if (type == InventoryAdjustmentType.in) {
            if (locationId == null) {
                inventoryBalanceRepository.ensureDefaultLocationBalanceExists(tenantId, branchId, productId);
            } else {
                inventoryBalanceRepository.ensureLocationBalanceExists(tenantId, branchId, productId, locationId);
            }
        }
        return (locationId == null
                        ? inventoryBalanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                                tenantId, branchId, productId)
                        : inventoryBalanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationId(
                                tenantId, branchId, productId, locationId))
                .orElseThrow(InventoryTraceabilityAdjustmentService::insufficientAggregateStock);
    }

    private static void ensureAggregateAvailable(
            InventoryBalance balance, BigDecimal quantity, InventoryAdjustmentType type) {
        if (type == InventoryAdjustmentType.out
                && quantity.compareTo(balance.getQuantity().subtract(balance.getReservedQuantity())) > 0) {
            throw insufficientAggregateStock();
        }
    }

    private InventoryLot resolveLot(
            UUID tenantId,
            Product product,
            InventoryAdjustmentRequest request,
            TraceInput trace) {
        if (!trace.trackingLot()) return null;
        if (request.type() == InventoryAdjustmentType.out) {
            return lotRepository.findByTenantIdAndId(tenantId, request.lotId())
                    .filter(lot -> lot.getProductId().equals(product.getId()))
                    .orElseThrow(InventoryTraceabilityAdjustmentService::lotNotFound);
        }

        lotRepository.ensureExists(
                UUID.randomUUID(), tenantId, product.getId(), trace.lotNumber(), request.expirationDate());
        InventoryLot lot = lotRepository
                .findByTenantIdAndProductIdAndLotNumber(tenantId, product.getId(), trace.lotNumber())
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el lote."));
        if (trace.trackingExpiration()
                && !Objects.equals(lot.getExpirationDate(), request.expirationDate())) {
            throw BusinessException.conflict(
                    "LOT_EXPIRATION_MISMATCH",
                    "La fecha de vencimiento no coincide con la registrada para el lote.");
        }
        return lot;
    }

    private InventoryLotBalance resolveLotBalance(
            UUID tenantId,
            UUID branchId,
            Product product,
            UUID locationId,
            InventoryAdjustmentType type,
            InventoryLot lot) {
        if (lot == null) return null;
        if (type == InventoryAdjustmentType.in) {
            if (locationId == null) {
                lotBalanceRepository.ensureWithoutLocationExists(
                        UUID.randomUUID(), tenantId, branchId, lot.getId());
            } else {
                lotBalanceRepository.ensureAtLocationExists(
                        UUID.randomUUID(), tenantId, branchId, locationId, lot.getId());
            }
        }
        return (locationId == null
                        ? lotBalanceRepository.findForUpdateWithoutLocation(
                                tenantId, branchId, product.getId(), lot.getId())
                        : lotBalanceRepository.findForUpdateAtLocation(
                                tenantId, branchId, product.getId(), lot.getId(), locationId))
                .orElseThrow(InventoryTraceabilityAdjustmentService::insufficientLotStock);
    }

    private static void ensureLotAvailable(
            InventoryLotBalance balance, BigDecimal quantity, InventoryAdjustmentType type) {
        if (balance != null && type == InventoryAdjustmentType.out
                && quantity.compareTo(balance.getQuantity().subtract(balance.getReservedQuantity())) > 0) {
            throw insufficientLotStock();
        }
    }

    private List<InventorySerial> resolveSerials(
            UUID tenantId,
            UUID branchId,
            Product product,
            Location location,
            InventoryLot lot,
            InventoryAdjustmentType type,
            TraceInput trace) {
        if (!trace.trackingSerial()) return List.of();
        if (type == InventoryAdjustmentType.in) {
            if (!serialRepository.findByTenantIdAndProductIdAndSerialNumberIn(
                    tenantId, product.getId(), trace.serialNumbers()).isEmpty()) {
                throw duplicateSerial();
            }
            return List.of();
        }

        List<InventorySerial> serials = serialRepository.findAllForUpdateByTenantProductAndSerialNumberIn(
                tenantId, product.getId(), trace.serialNumbers());
        if (serials.size() != trace.serialNumbers().size()) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "SERIAL_NOT_FOUND", "Uno o mas numeros de serie no existen.");
        }
        UUID locationId = location == null ? null : location.getId();
        for (InventorySerial serial : serials) {
            if (serial.getStatus() != InventorySerialStatus.AVAILABLE) {
                throw BusinessException.conflict(
                        "SERIAL_UNAVAILABLE", "Uno o mas numeros de serie no estan disponibles.");
            }
            if (!serial.getBranchId().equals(branchId)
                    || !Objects.equals(serial.getLocationId(), locationId)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOCATION_MISMATCH",
                        "Uno o mas numeros de serie no pertenecen a la sucursal y ubicacion indicadas.");
            }
            if (lot != null && !Objects.equals(serial.getLotId(), lot.getId())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOT_MISMATCH",
                        "Uno o mas numeros de serie no pertenecen al lote indicado.");
            }
            if (lot == null && serial.getLotId() != null) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "SERIAL_LOT_MISMATCH",
                        "El numero de serie pertenece a un lote no solicitado.");
            }
        }
        return serials;
    }

    private List<InventorySerial> createSerials(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID locationId,
            InventoryLot lot,
            List<String> serialNumbers) {
        List<InventorySerial> serials = serialNumbers.stream()
                .map(number -> {
                    InventorySerial serial = InventorySerial.builder()
                            .branchId(branchId)
                            .locationId(locationId)
                            .productId(productId)
                            .serialNumber(number)
                            .lotId(lot == null ? null : lot.getId())
                            .status(InventorySerialStatus.AVAILABLE)
                            .build();
                    serial.setTenantId(tenantId);
                    return serial;
                })
                .toList();
        try {
            return serialRepository.saveAllAndFlush(serials);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateSerial();
        }
    }

    private static void mutateAggregate(
            InventoryBalance balance, BigDecimal quantity, InventoryAdjustmentType type) {
        if (type == InventoryAdjustmentType.in) balance.add(quantity);
        else {
            try {
                balance.deduct(quantity);
            } catch (IllegalStateException exception) {
                throw insufficientAggregateStock();
            }
        }
    }

    private static void mutateLotBalance(
            InventoryLotBalance balance, BigDecimal quantity, InventoryAdjustmentType type) {
        if (balance == null) return;
        if (type == InventoryAdjustmentType.in) balance.add(quantity);
        else {
            try {
                balance.deduct(quantity);
            } catch (IllegalStateException exception) {
                throw insufficientLotStock();
            }
        }
    }

    private void saveTraces(
            UUID tenantId,
            UUID movementId,
            InventoryLot lot,
            List<InventorySerial> serials,
            TraceInput trace,
            BigDecimal quantity) {
        List<InventoryMovementTrace> traces = new ArrayList<>();
        if (trace.trackingSerial()) {
            serials.forEach(serial -> traces.add(InventoryMovementTrace.builder()
                    .tenantId(tenantId)
                    .movementId(movementId)
                    .serialId(serial.getId())
                    .quantity(BigDecimal.ONE)
                    .build()));
        } else if (trace.trackingLot()) {
            traces.add(InventoryMovementTrace.builder()
                    .tenantId(tenantId)
                    .movementId(movementId)
                    .lotId(lot.getId())
                    .quantity(quantity)
                    .build());
        }
        if (!traces.isEmpty()) movementTraceRepository.saveAllAndFlush(traces);
    }

    private LocalDate businessDate(UUID tenantId) {
        ZoneId zone = tenantRepository.findById(tenantId)
                .map(Tenant::getTimezone)
                .map(InventoryTraceabilityAdjustmentService::zoneId)
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
        List<String> normalized = values.stream().map(InventoryTraceabilityAdjustmentService::trimToNull).toList();
        if (normalized.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "SERIAL_INVALID", "Los numeros de serie no pueden estar vacios.");
        }
        return normalized.stream().sorted().toList();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static BusinessException invalidTracking(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRACKING_PAYLOAD", message);
    }

    private static BusinessException lotNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Lote no encontrado.");
    }

    private static BusinessException insufficientLotStock() {
        return BusinessException.conflict(
                "INSUFFICIENT_TRACEABLE_LOT_STOCK",
                "No existe stock trazable suficiente en el lote indicado.");
    }

    private static BusinessException duplicateSerial() {
        return BusinessException.conflict(
                "DUPLICATE_SERIAL", "Uno o mas numeros de serie ya existen.");
    }

    private static BusinessException insufficientAggregateStock() {
        return BusinessException.conflict("INSUFFICIENT_STOCK", "Stock insuficiente.");
    }

    private record TraceInput(
            boolean trackingLot,
            boolean trackingExpiration,
            boolean trackingSerial,
            String lotNumber,
            List<String> serialNumbers) {}
}
