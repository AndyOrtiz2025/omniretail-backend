package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.inventory.dto.InventoryCountResultResponse;
import com.omniretail.backend.inventory.dto.InventoryCountResultResponse.LotResult;
import com.omniretail.backend.inventory.dto.InventoryCountSnapshotResponse;
import com.omniretail.backend.inventory.dto.InventoryInboundCommand;
import com.omniretail.backend.inventory.dto.InventoryInboundTraceDetail;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.Addition;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.LotCount;
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
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Conteo fisico trazable aplicado atomicamente contra un snapshot bloqueado. */
@Service
@RequiredArgsConstructor
public class InventoryCountService {

    static final String REFERENCE_TYPE = "count_correction";
    private static final Set<InventorySerialStatus> PHYSICAL_STATUSES =
            EnumSet.of(InventorySerialStatus.AVAILABLE, InventorySerialStatus.RESERVED);
    private static final UUID NO_LOT = new UUID(0L, 0L);

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final UnitRepository unitRepository;
    private final UserRepository userRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryLotRepository lotRepository;
    private final InventoryLotBalanceRepository lotBalanceRepository;
    private final InventorySerialRepository serialRepository;
    private final InventoryMovementRepository movementRepository;
    private final InventoryMovementTraceRepository movementTraceRepository;
    private final InventoryTraceabilityMutationService mutationService;
    private final InventoryOperationalLocationService operationalLocations;
    private final EntityManager entityManager;

    @Transactional
    public InventoryCountSnapshotResponse snapshot(UUID branchId, UUID productId, UUID locationId) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        Scope scope = requireScope(actor, branchId, productId, locationId);
        Flags flags = requireTraceable(scope.product());

        InventoryBalance balance = balanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId)
                .stream()
                .filter(candidate -> Objects.equals(candidate.getLocationId(), scope.locationId()))
                .findFirst()
                .orElse(null);
        BigDecimal quantity = balance == null ? BigDecimal.ZERO : balance.getQuantity();
        BigDecimal reserved = balance == null ? BigDecimal.ZERO : balance.getReservedQuantity();

        List<InventoryLotBalance> lotBalances = flags.lot()
                ? lotBalanceRepository.findByTenantBranchAndProduct(tenantId, branchId, productId).stream()
                        .filter(candidate -> Objects.equals(candidate.getLocationId(), scope.locationId()))
                        .filter(candidate -> candidate.getQuantity().signum() > 0
                                || candidate.getReservedQuantity().signum() > 0)
                        .toList()
                : List.of();
        Map<UUID, InventoryLot> lots = lotsById(tenantId, lotBalances);
        List<InventorySerial> physicalSerials = flags.serial()
                ? serialRepository.findByTenantIdAndBranchIdAndProductIdOrderBySerialNumberAsc(
                                tenantId, branchId, productId)
                        .stream()
                        .filter(serial -> Objects.equals(serial.getLocationId(), scope.locationId()))
                        .filter(serial -> PHYSICAL_STATUSES.contains(serial.getStatus()))
                        .toList()
                : List.of();
        Map<UUID, List<InventorySerial>> serialsByLot = physicalSerials.stream()
                .filter(serial -> serial.getLotId() != null)
                .collect(Collectors.groupingBy(InventorySerial::getLotId));

        List<InventoryCountSnapshotResponse.Lot> lotResponses = lotBalances.stream()
                .sorted(Comparator
                        .comparing((InventoryLotBalance candidate) ->
                                lots.get(candidate.getLotId()).getExpirationDate(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(candidate -> lots.get(candidate.getLotId()).getLotNumber()))
                .map(candidate -> {
                    InventoryLot lot = lots.get(candidate.getLotId());
                    return new InventoryCountSnapshotResponse.Lot(
                            lot.getId(),
                            lot.getLotNumber(),
                            lot.getExpirationDate(),
                            candidate.getQuantity(),
                            candidate.getReservedQuantity(),
                            candidate.getQuantity().subtract(candidate.getReservedQuantity()),
                            serialResponses(serialsByLot.getOrDefault(lot.getId(), List.of())));
                })
                .toList();
        List<InventoryCountSnapshotResponse.Serial> looseSerials = serialResponses(physicalSerials.stream()
                .filter(serial -> serial.getLotId() == null)
                .toList());

        return new InventoryCountSnapshotResponse(
                scope.product().getId(), scope.product().getName(), scope.product().getSku(),
                scope.branch().getId(), scope.branch().getName(), scope.locationId(),
                scope.location() == null ? null : scope.location().getName(),
                quantity, reserved, quantity.subtract(reserved),
                new InventoryCountSnapshotResponse.Tracking(flags.lot(), flags.expiration(), flags.serial()),
                lotResponses, looseSerials);
    }

    private static List<InventoryCountSnapshotResponse.Serial> serialResponses(
            List<InventorySerial> serials) {
        return serials.stream()
                .map(serial -> new InventoryCountSnapshotResponse.Serial(
                        serial.getId(), serial.getSerialNumber(), serial.getStatus()))
                .toList();
    }

    @Transactional
    public InventoryCountResultResponse reconcile(ReconcileInventoryCountRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        Scope scope = requireScope(actor, request.branchId(), request.productId(), request.locationId());
        Product product = scope.product();
        Flags flags = requireTraceable(product);
        UUID branchId = scope.branch().getId();
        UUID locationId = scope.locationId();
        UUID countId = UUID.randomUUID();
        String reason = request.reason().trim();
        Unit baseUnit = unitRepository.findByTenantIdAndId(tenantId, product.getBaseUnitId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "Unidad no encontrada."));

        List<InventoryInboundTraceDetail> additions =
                normalizeAdditions(tenantId, product, request.additions());

        InventoryBalance balance = lockBalance(tenantId, branchId, product.getId(), locationId);
        requireExpected(request.expectedQuantity(), balance.getQuantity(), "el producto");
        List<InventoryLotBalance> lotBalances = flags.lot()
                ? lotBalanceRepository.findAllForUpdateAtLocation(
                        tenantId, branchId, product.getId(), locationId)
                : List.of();
        List<InventorySerial> physical = flags.serial()
                ? serialRepository.findPhysicalForUpdateAtLocation(
                        tenantId, branchId, product.getId(), locationId, PHYSICAL_STATUSES)
                : List.of();
        requireConsistentPhysicalState(balance, lotBalances, physical, flags);

        List<LotPlan> plans;
        if (flags.lot() && !flags.serial()) {
            plans = planLotOnly(tenantId, request, lotBalances, baseUnit);
        } else if (flags.serial() && !flags.lot()) {
            plans = planSerialOnly(tenantId, request, product, branchId, locationId, physical);
        } else {
            plans = planLotAndSerial(
                    tenantId, request, product, branchId, locationId, lotBalances, physical);
        }

        BigDecimal totalMissing = plans.stream()
                .map(LotPlan::missingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (balance.getQuantity().subtract(totalMissing).compareTo(balance.getReservedQuantity()) < 0) {
            throw belowReserved();
        }

        BigDecimal quantityBefore = balance.getQuantity();
        List<UUID> movementIds = new ArrayList<>();
        for (LotPlan plan : plans) {
            BigDecimal missing = plan.missingQuantity();
            if (missing.signum() == 0) continue;
            if (plan.balance() != null) {
                try {
                    plan.balance().deduct(missing);
                } catch (IllegalStateException exception) {
                    throw belowReserved();
                }
            }
            plan.missing().forEach(serial -> serial.setStatus(InventorySerialStatus.WRITTEN_OFF));
            BigDecimal before = balance.getQuantity();
            try {
                balance.deduct(missing);
            } catch (IllegalStateException exception) {
                throw belowReserved();
            }
            InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                    .tenantId(tenantId)
                    .branchId(branchId)
                    .productId(product.getId())
                    .type(InventoryMovementType.out)
                    .reason(reason)
                    .quantity(missing)
                    .quantityBefore(before)
                    .quantityAfter(balance.getQuantity())
                    .fromLocationId(locationId)
                    .referenceType(REFERENCE_TYPE)
                    .referenceId(countId)
                    .performedByUserId(actor.userId())
                    .build());
            saveOutTraces(tenantId, movement.getId(), flags, plan, missing);
            movementIds.add(movement.getId());
        }

        Map<UUID, LotAcc> results = resultAccumulators(plans);
        BigDecimal addedQuantity = BigDecimal.ZERO;
        for (InventoryInboundTraceDetail addition : additions) {
            InventoryMovement movement = mutationService.receive(new InventoryInboundCommand(
                    tenantId, branchId, product, locationId, addition.baseQuantity(), List.of(addition),
                    reason, REFERENCE_TYPE, countId, null, actor.userId()));
            movementIds.add(movement.getId());
            addedQuantity = addedQuantity.add(addition.baseQuantity());
            InventoryLot lot = addition.lotNumber() == null
                    ? null
                    : lotRepository.findByTenantIdAndProductIdAndLotNumber(
                                    tenantId, product.getId(), addition.lotNumber())
                            .orElseThrow(() -> new IllegalStateException("No se pudo resolver el lote agregado."));
            LotAcc acc = results.computeIfAbsent(
                    lot == null ? NO_LOT : lot.getId(),
                    key -> new LotAcc(
                            lot == null ? null : lot.getId(),
                            lot == null ? null : lot.getLotNumber(),
                            lot == null ? null : lot.getExpirationDate(),
                            BigDecimal.ZERO));
            acc.counted = acc.counted.add(addition.baseQuantity());
            acc.added = new ArrayList<>(acc.added);
            acc.added.addAll(addition.serialNumbers());
        }

        BigDecimal counted = plans.stream()
                .map(LotPlan::counted)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(addedQuantity);
        BigDecimal quantityAfter = balance.getQuantity();
        String performer = userRepository.findByTenantIdAndId(tenantId, actor.userId())
                .map(User::getName)
                .orElse(null);
        return new InventoryCountResultResponse(
                countId, Instant.now(), product.getId(), product.getName(), product.getSku(),
                scope.branch().getId(), scope.branch().getName(), locationId,
                scope.location() == null ? null : scope.location().getName(),
                actor.userId(), performer, quantityBefore, counted, quantityAfter,
                quantityAfter.subtract(quantityBefore),
                results.values().stream()
                        .sorted(Comparator
                                .comparing((LotAcc acc) -> acc.expirationDate,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(acc -> acc.lotNumber,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                        .map(LotAcc::toResult)
                        .toList(),
                movementIds);
    }

    private static Map<UUID, LotAcc> resultAccumulators(List<LotPlan> plans) {
        Map<UUID, LotAcc> results = new LinkedHashMap<>();
        for (LotPlan plan : plans) {
            LotAcc acc = new LotAcc(
                    plan.lot() == null ? null : plan.lot().getId(),
                    plan.lot() == null ? null : plan.lot().getLotNumber(),
                    plan.lot() == null ? null : plan.lot().getExpirationDate(),
                    plan.before());
            acc.counted = plan.counted();
            acc.found = plan.found();
            acc.missingNumbers = plan.missing().stream()
                    .map(InventorySerial::getSerialNumber)
                    .sorted()
                    .toList();
            results.put(plan.lot() == null ? NO_LOT : plan.lot().getId(), acc);
        }
        return results;
    }

    private List<LotPlan> planLotOnly(
            UUID tenantId,
            ReconcileInventoryCountRequest request,
            List<InventoryLotBalance> lotBalances,
            Unit baseUnit) {
        requireNoLooseSerials(request.foundSerialNumbers());
        Map<UUID, LotCount> requested = indexLots(request.lots());
        Map<UUID, InventoryLot> lots = lotsById(tenantId, lotBalances);
        List<LotPlan> plans = new ArrayList<>();
        for (InventoryLotBalance lotBalance : lotBalances) {
            LotCount lotCount = requested.remove(lotBalance.getLotId());
            InventoryLot lot = lots.get(lotBalance.getLotId());
            if (lotCount == null) {
                throw stale("El lote " + lot.getLotNumber() + " no fue incluido en el conteo.");
            }
            requireExpected(lotCount.expectedQuantity(), lotBalance.getQuantity(),
                    "el lote " + lot.getLotNumber());
            BigDecimal counted = lotCount.countedQuantity();
            if (counted == null) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "COUNT_QUANTITY_REQUIRED",
                        "La cantidad encontrada es obligatoria para el lote " + lot.getLotNumber() + ".");
            }
            if (!baseUnit.getAllowsDecimals() && counted.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "COUNT_INVALID_QUANTITY",
                        "La unidad base solo admite cantidades enteras.");
            }
            if (counted.compareTo(lotBalance.getQuantity()) > 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "COUNT_QUANTITY_EXCEEDS_REGISTERED",
                        "Lo encontrado en el lote supera lo registrado; registra el excedente como unidad adicional.");
            }
            if (counted.compareTo(lotBalance.getReservedQuantity()) < 0) throw belowReserved();
            plans.add(new LotPlan(
                    lot, lotBalance, lotBalance.getQuantity(), counted, List.of(), List.of(),
                    lotBalance.getQuantity().subtract(counted)));
        }
        requireNoUnknownLots(requested);
        return plans;
    }

    private List<LotPlan> planSerialOnly(
            UUID tenantId,
            ReconcileInventoryCountRequest request,
            Product product,
            UUID branchId,
            UUID locationId,
            List<InventorySerial> physical) {
        if (request.lots() != null && !request.lots().isEmpty()) {
            throw invalidPayload("El producto no usa lotes; envia los seriales encontrados sin lote.");
        }
        return List.of(serialPlan(
                tenantId, product, branchId, locationId, null, null, physical,
                request.expectedSerialNumbers(), request.foundSerialNumbers(), null, new HashSet<>()));
    }

    private List<LotPlan> planLotAndSerial(
            UUID tenantId,
            ReconcileInventoryCountRequest request,
            Product product,
            UUID branchId,
            UUID locationId,
            List<InventoryLotBalance> lotBalances,
            List<InventorySerial> physical) {
        requireNoLooseSerials(request.foundSerialNumbers());
        requireNoLooseSerials(request.expectedSerialNumbers());
        Map<UUID, LotCount> requested = indexLots(request.lots());
        Map<UUID, InventoryLot> lots = lotsById(tenantId, lotBalances);
        Set<String> globalFound = new HashSet<>();
        List<LotPlan> plans = new ArrayList<>();
        for (InventoryLotBalance lotBalance : lotBalances) {
            LotCount lotCount = requested.remove(lotBalance.getLotId());
            InventoryLot lot = lots.get(lotBalance.getLotId());
            if (lotCount == null) {
                throw stale("El lote " + lot.getLotNumber() + " no fue incluido en el conteo.");
            }
            requireExpected(lotCount.expectedQuantity(), lotBalance.getQuantity(),
                    "el lote " + lot.getLotNumber());
            List<InventorySerial> group = physical.stream()
                    .filter(serial -> Objects.equals(serial.getLotId(), lot.getId()))
                    .toList();
            plans.add(serialPlan(
                    tenantId, product, branchId, locationId, lot, lotBalance, group,
                    lotCount.expectedSerialNumbers(), lotCount.foundSerialNumbers(),
                    lotCount.countedQuantity(), globalFound));
        }
        requireNoUnknownLots(requested);
        return plans;
    }

    private LotPlan serialPlan(
            UUID tenantId,
            Product product,
            UUID branchId,
            UUID locationId,
            InventoryLot lot,
            InventoryLotBalance lotBalance,
            List<InventorySerial> group,
            List<String> expectedRaw,
            List<String> foundRaw,
            BigDecimal declaredCount,
            Set<String> globalFound) {
        Set<String> expected = normalizeExpectedSerials(expectedRaw);
        Set<String> actual = group.stream()
                .map(InventorySerial::getSerialNumber)
                .collect(Collectors.toSet());
        if (!expected.equals(actual)) {
            throw stale("Los seriales cambiaron desde que se abrio el conteo.");
        }
        List<String> found = new ArrayList<>();
        for (String raw : foundRaw == null ? List.<String>of() : foundRaw) {
            String serial = InventoryTraceabilityMutationService.normalizeSerialNumber(raw);
            if (!globalFound.add(serial)) {
                throw BusinessException.conflict(
                        "DUPLICATE_SERIAL", "Un numero de serie no puede repetirse en el conteo.");
            }
            found.add(serial);
        }
        found.sort(Comparator.naturalOrder());
        Map<String, InventorySerial> byNumber = group.stream()
                .collect(Collectors.toMap(InventorySerial::getSerialNumber, Function.identity()));
        List<String> unknown = found.stream().filter(number -> !byNumber.containsKey(number)).toList();
        if (!unknown.isEmpty()) {
            rejectUnknownSerials(tenantId, product, branchId, locationId, unknown);
        }
        if (declaredCount != null && declaredCount.compareTo(BigDecimal.valueOf(found.size())) != 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "SERIAL_COUNT_MISMATCH",
                    "La cantidad encontrada debe coincidir con el numero de series encontrados.");
        }
        Set<String> foundSet = new HashSet<>(found);
        List<InventorySerial> missing = group.stream()
                .filter(serial -> !foundSet.contains(serial.getSerialNumber()))
                .toList();
        if (missing.stream().anyMatch(serial -> serial.getStatus() == InventorySerialStatus.RESERVED)) {
            throw BusinessException.conflict(
                    "COUNT_RESERVED_SERIAL_MISSING",
                    "Un serial reservado no puede darse por faltante sin liberar antes su reserva.");
        }
        BigDecimal before = lotBalance == null
                ? BigDecimal.valueOf(group.size())
                : lotBalance.getQuantity();
        return new LotPlan(
                lot, lotBalance, before, BigDecimal.valueOf(found.size()), found, missing,
                BigDecimal.valueOf(missing.size()));
    }

    private void rejectUnknownSerials(
            UUID tenantId, Product product, UUID branchId, UUID locationId, List<String> unknown) {
        Map<String, InventorySerial> known = serialRepository
                .findByTenantIdAndProductIdAndSerialNumberIn(tenantId, product.getId(), unknown)
                .stream()
                .collect(Collectors.toMap(InventorySerial::getSerialNumber, Function.identity()));
        for (String number : unknown) {
            InventorySerial serial = known.get(number);
            if (serial == null) {
                throw new BusinessException(
                        HttpStatus.NOT_FOUND, "SERIAL_NOT_FOUND", "Uno o mas numeros de serie no existen.");
            }
            if (!serial.getBranchId().equals(branchId)
                    || !Objects.equals(serial.getLocationId(), locationId)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "SERIAL_LOCATION_MISMATCH",
                        "Uno o mas numeros de serie no pertenecen a la ubicacion indicada.");
            }
            if (!PHYSICAL_STATUSES.contains(serial.getStatus())) {
                throw BusinessException.conflict(
                        "SERIAL_NOT_PRESENT", "Uno o mas numeros de serie ya no estan presentes.");
            }
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "SERIAL_LOT_MISMATCH",
                    "Uno o mas numeros de serie no pertenecen al lote indicado.");
        }
    }

    private List<InventoryInboundTraceDetail> normalizeAdditions(
            UUID tenantId, Product product, List<Addition> additions) {
        if (additions == null || additions.isEmpty()) return List.of();
        List<InventoryInboundTraceDetail> normalized = new ArrayList<>();
        Set<String> serials = new HashSet<>();
        for (Addition addition : additions) {
            List<InventoryInboundTraceDetail> details = mutationService.validateAndNormalize(
                    tenantId,
                    product,
                    addition.quantity(),
                    List.of(new InventoryInboundTraceDetail(
                            addition.quantity(), addition.lotNumber(), addition.expirationDate(),
                            addition.serialNumbers())));
            for (InventoryInboundTraceDetail detail : details) {
                for (String serial : detail.serialNumbers()) {
                    if (!serials.add(serial)) {
                        throw BusinessException.conflict(
                                "DUPLICATE_SERIAL", "Un numero de serie no puede repetirse en el conteo.");
                    }
                }
                normalized.add(detail);
            }
        }
        return normalized;
    }

    private InventoryBalance lockBalance(
            UUID tenantId, UUID branchId, UUID productId, UUID locationId) {
        balanceRepository.ensureLocationBalanceExists(tenantId, branchId, productId, locationId);
        InventoryBalance balance = balanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationId(
                        tenantId, branchId, productId, locationId)
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el balance."));
        // La politica operativa puede haber cargado este balance antes de adquirir el lock. Se
        // refresca ya bloqueado para que la precondicion compare el estado confirmado mas reciente.
        entityManager.refresh(balance);
        return balance;
    }

    private Scope requireScope(
            AuthenticatedUser actor, UUID branchId, UUID productId, UUID requestedLocationId) {
        UUID tenantId = actor.tenantId();
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (!branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        requireTraceable(product);
        validateRequestedLocation(tenantId, branchId, requestedLocationId);
        UUID locationId = operationalLocations.resolveForInbound(
                        tenantId, branchId, productId, requestedLocationId)
                .locationId();
        if (locationId == null) {
            throw BusinessException.conflict(
                    InventoryOperationalLocationService.LOCATION_REQUIRED_CODE,
                    "El conteo trazable requiere una ubicacion operativa explicita.");
        }
        Location location = validateRequestedLocation(tenantId, branchId, locationId);
        return new Scope(branch, product, location, locationId);
    }

    private Location validateRequestedLocation(UUID tenantId, UUID branchId, UUID locationId) {
        if (locationId == null) return null;
        Location location = locationRepository.findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "Ubicacion no encontrada."));
        if (!location.getBranchId().equals(branchId)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "LOCATION_BRANCH_MISMATCH",
                    "La ubicacion no pertenece a la sucursal indicada.");
        }
        if (location.getStatus() != LocationStatus.active) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "LOCATION_NOT_ACTIVE",
                    "La ubicacion debe estar activa para realizar el conteo.");
        }
        return location;
    }

    private static Flags requireTraceable(Product product) {
        boolean stock = product.getProductType() == ProductType.physical
                && Boolean.TRUE.equals(product.getTrackingStock());
        Flags flags = new Flags(
                stock && Boolean.TRUE.equals(product.getTrackingLot()),
                stock && Boolean.TRUE.equals(product.getTrackingLot())
                        && Boolean.TRUE.equals(product.getTrackingExpiration()),
                stock && Boolean.TRUE.equals(product.getTrackingSerial()));
        if (!flags.lot() && !flags.serial()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "COUNT_PRODUCT_NOT_TRACEABLE",
                    "El conteo trazable solo aplica a productos con lote o serie.");
        }
        return flags;
    }

    private static void requireConsistentPhysicalState(
            InventoryBalance aggregate,
            List<InventoryLotBalance> lotBalances,
            List<InventorySerial> physical,
            Flags flags) {
        if (flags.lot()) {
            BigDecimal lotQuantity = lotBalances.stream()
                    .map(InventoryLotBalance::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal lotReserved = lotBalances.stream()
                    .map(InventoryLotBalance::getReservedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lotQuantity.compareTo(aggregate.getQuantity()) != 0
                    || lotReserved.compareTo(aggregate.getReservedQuantity()) > 0) {
                throw inconsistentTraceability();
            }
        }
        if (flags.serial()) {
            long reservedSerials = physical.stream()
                    .filter(serial -> serial.getStatus() == InventorySerialStatus.RESERVED)
                    .count();
            if (aggregate.getQuantity().compareTo(BigDecimal.valueOf(physical.size())) != 0
                    || BigDecimal.valueOf(reservedSerials)
                                    .compareTo(aggregate.getReservedQuantity())
                            > 0) {
                throw inconsistentTraceability();
            }
            if (flags.lot()) {
                Map<UUID, Long> serialsByLot = physical.stream()
                        .filter(serial -> serial.getLotId() != null)
                        .collect(Collectors.groupingBy(InventorySerial::getLotId, Collectors.counting()));
                Map<UUID, Long> reservedByLot = physical.stream()
                        .filter(serial -> serial.getLotId() != null)
                        .filter(serial -> serial.getStatus() == InventorySerialStatus.RESERVED)
                        .collect(Collectors.groupingBy(InventorySerial::getLotId, Collectors.counting()));
                boolean mismatch = physical.stream().anyMatch(serial -> serial.getLotId() == null)
                        || lotBalances.stream().anyMatch(lotBalance -> lotBalance.getQuantity().compareTo(
                                        BigDecimal.valueOf(serialsByLot.getOrDefault(lotBalance.getLotId(), 0L)))
                                        != 0
                                || lotBalance.getReservedQuantity().compareTo(BigDecimal.valueOf(
                                                reservedByLot.getOrDefault(lotBalance.getLotId(), 0L)))
                                        != 0);
                if (mismatch) throw inconsistentTraceability();
            }
        }
    }

    private Map<UUID, InventoryLot> lotsById(
            UUID tenantId, Collection<InventoryLotBalance> lotBalances) {
        if (lotBalances.isEmpty()) return Map.of();
        return lotRepository.findByTenantIdAndIdIn(
                        tenantId,
                        lotBalances.stream().map(InventoryLotBalance::getLotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
    }

    private static Map<UUID, LotCount> indexLots(List<LotCount> lots) {
        Map<UUID, LotCount> indexed = new HashMap<>();
        for (LotCount lot : lots == null ? List.<LotCount>of() : lots) {
            if (lot == null || lot.lotId() == null || indexed.put(lot.lotId(), lot) != null) {
                throw invalidPayload("Cada lote debe aparecer una sola vez en el conteo.");
            }
        }
        return indexed;
    }

    private static Set<String> normalizeExpectedSerials(List<String> raw) {
        if (raw == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "COUNT_EXPECTED_SERIALS_REQUIRED",
                    "Debes enviar los seriales esperados del snapshot.");
        }
        Set<String> expected = new HashSet<>();
        for (String serial : raw) {
            if (!expected.add(InventoryTraceabilityMutationService.normalizeSerialNumber(serial))) {
                throw BusinessException.conflict(
                        "DUPLICATE_SERIAL", "Un numero de serie no puede repetirse en el conteo.");
            }
        }
        return expected;
    }

    private static void requireNoLooseSerials(List<String> serials) {
        if (serials != null && !serials.isEmpty()) {
            throw invalidPayload("Para este producto los seriales se envian dentro de cada lote.");
        }
    }

    private static void requireNoUnknownLots(Map<UUID, LotCount> remaining) {
        if (!remaining.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "LOT_NOT_FOUND",
                    "Uno o mas lotes no existen en la ubicacion contada.");
        }
    }

    private static void requireExpected(BigDecimal expected, BigDecimal current, String subject) {
        if (expected == null || expected.compareTo(current) != 0) {
            throw stale("La cantidad registrada de " + subject + " cambio desde que se abrio el conteo.");
        }
    }

    private void saveOutTraces(
            UUID tenantId, UUID movementId, Flags flags, LotPlan plan, BigDecimal quantity) {
        List<InventoryMovementTrace> traces = new ArrayList<>();
        if (flags.serial()) {
            plan.missing().forEach(serial -> traces.add(InventoryMovementTrace.builder()
                    .tenantId(tenantId)
                    .movementId(movementId)
                    .serialId(serial.getId())
                    .quantity(BigDecimal.ONE)
                    .build()));
        } else if (plan.lot() != null) {
            traces.add(InventoryMovementTrace.builder()
                    .tenantId(tenantId)
                    .movementId(movementId)
                    .lotId(plan.lot().getId())
                    .quantity(quantity)
                    .build());
        }
        if (!traces.isEmpty()) movementTraceRepository.saveAllAndFlush(traces);
    }

    private static BusinessException stale(String message) {
        return BusinessException.conflict(
                "COUNT_SNAPSHOT_STALE", message + " Vuelve a cargar el conteo.");
    }

    private static BusinessException belowReserved() {
        return BusinessException.conflict(
                "COUNT_BELOW_RESERVED",
                "El conteo dejaria menos existencia fisica que la cantidad reservada.");
    }

    private static BusinessException inconsistentTraceability() {
        return BusinessException.conflict(
                "COUNT_TRACEABILITY_INCONSISTENT",
                "El balance agregado no coincide con su composicion trazable; no se aplico el conteo.");
    }

    private static BusinessException invalidPayload(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "COUNT_INVALID_PAYLOAD", message);
    }

    private record Scope(Branch branch, Product product, Location location, UUID locationId) {}

    private record Flags(boolean lot, boolean expiration, boolean serial) {}

    private record LotPlan(
            InventoryLot lot,
            InventoryLotBalance balance,
            BigDecimal before,
            BigDecimal counted,
            List<String> found,
            List<InventorySerial> missing,
            BigDecimal missingQuantity) {}

    private static final class LotAcc {
        final UUID lotId;
        final String lotNumber;
        final java.time.LocalDate expirationDate;
        final BigDecimal before;
        BigDecimal counted = BigDecimal.ZERO;
        List<String> found = List.of();
        List<String> missingNumbers = List.of();
        List<String> added = List.of();

        LotAcc(
                UUID lotId,
                String lotNumber,
                java.time.LocalDate expirationDate,
                BigDecimal before) {
            this.lotId = lotId;
            this.lotNumber = lotNumber;
            this.expirationDate = expirationDate;
            this.before = before;
        }

        LotResult toResult() {
            return new LotResult(
                    lotId, lotNumber, expirationDate, before, counted,
                    counted.subtract(before), found, missingNumbers,
                    added.stream().sorted().toList());
        }
    }
}
