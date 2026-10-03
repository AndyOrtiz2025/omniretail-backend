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

    private record ResolvedDetail(InventoryInboundTraceDetail detail, InventoryLot lot) {}
}
