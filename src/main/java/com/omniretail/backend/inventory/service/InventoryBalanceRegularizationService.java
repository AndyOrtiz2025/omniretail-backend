package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse.Blocker;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizationOptionsResponse;
import com.omniretail.backend.inventory.dto.RegularizationOptionsResponse.LocationOption;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.entity.InventoryBalanceRegularization;
import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementTrace;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import com.omniretail.backend.inventory.repository.InventoryBalanceRegularizationRepository;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryLotBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementTraceRepository;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.logistics.entity.PickingItem;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingStatus;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regularizacion administrativa del balance heredado sin ubicacion ({@code location_id = NULL}): lo
 * consolida en la ubicacion operativa ya asignada al producto, sin cambiar existencias totales, reservas,
 * lotes ni series, y sin tocar movimientos historicos.
 *
 * <p>Una sola transaccion por producto, sucursal y destino. Bloqueos, en este orden (el mismo que usan el
 * ciclo de vida de reservas, despacho y cancelaciones: balances por id, luego reservas por id, luego lotes y
 * series):
 *
 * <ol>
 *   <li>configuracion y producto en modo compartido ({@link InventoryOperationalLocationService}); nunca
 *       {@code FOR UPDATE}, para no cruzarse con quien ya tiene un balance bloqueado e inserta movimientos.
 *       Con la asignacion inicial ({@code assignDestination} y producto sin ubicacion) el producto se toma en
 *       {@code FOR NO KEY UPDATE}: excluye a las decisiones concurrentes (que piden {@code FOR SHARE}) sin
 *       chocar con el {@code KEY SHARE} de las claves foraneas de los movimientos, y se toma antes de
 *       cualquier balance, asi que el orden configuracion -> producto -> balance no cambia;
 *   <li>balances agregados NULL y destino, ordenados por id;
 *   <li>reservas activas del producto, ordenadas por id;
 *   <li>balances de lote NULL (por lote) y los de destino del mismo lote;
 *   <li>series fisicas del balance NULL, ordenadas por numero.
 * </ol>
 *
 * <p>Picking no toma ninguno de los balances ni reservas y solo comparte lotes y series en el mismo orden
 * relativo. Las lineas de Picking solo se leen: si no pueden quedar coherentes (ubicacion distinta del destino
 * o seleccion fisica) la operacion se bloquea con 409 y causa especifica, sin migrar estado parcial.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryBalanceRegularizationService {

    public static final String REFERENCE_TYPE = "location_regularization";

    public static final String LOCATIONS_DISABLED_CODE = "INVENTORY_REGULARIZATION_LOCATIONS_DISABLED";
    public static final String PRODUCT_NOT_ELIGIBLE_CODE = "INVENTORY_REGULARIZATION_PRODUCT_NOT_ELIGIBLE";
    public static final String DESTINATION_NOT_ASSIGNED_CODE =
            "INVENTORY_REGULARIZATION_DESTINATION_NOT_ASSIGNED";
    public static final String DESTINATION_INACTIVE_CODE = "INVENTORY_REGULARIZATION_DESTINATION_INACTIVE";
    public static final String NOT_REQUIRED_CODE = "INVENTORY_REGULARIZATION_NOT_REQUIRED";
    public static final String THIRD_LOCATION_STOCK_CODE = "INVENTORY_REGULARIZATION_THIRD_LOCATION_STOCK";
    public static final String TRACEABILITY_INCONSISTENT_CODE =
            "INVENTORY_REGULARIZATION_TRACEABILITY_INCONSISTENT";
    public static final String TRACEABLE_RESERVATION_CODE = "INVENTORY_REGULARIZATION_TRACEABLE_RESERVATION";
    public static final String RESERVATION_INVALID_CODE = "INVENTORY_REGULARIZATION_RESERVATION_INVALID";
    public static final String RESERVATION_OTHER_BALANCE_CODE =
            "INVENTORY_REGULARIZATION_RESERVATION_OTHER_BALANCE";
    public static final String RESERVATION_DRIFT_CODE = "INVENTORY_REGULARIZATION_RESERVATION_DRIFT";
    public static final String PICKING_CONFLICT_CODE = "INVENTORY_REGULARIZATION_PICKING_CONFLICT";
    public static final String STALE_SNAPSHOT_CODE = "INVENTORY_REGULARIZATION_STALE_SNAPSHOT";
    public static final String IDEMPOTENCY_KEY_REUSED_CODE = "INVENTORY_REGULARIZATION_KEY_REUSED";
    public static final String INCONSISTENT_CODE = "INVENTORY_REGULARIZATION_INCONSISTENT";
    /** La asignacion inicial no puede aplicarse: el producto ya tiene (o acaba de recibir) otra ubicacion. */
    public static final String ASSIGNMENT_CONFLICT_CODE = "INVENTORY_REGULARIZATION_ASSIGNMENT_CONFLICT";
    /** Vista previa: el usuario no reune los permisos que exige la asignacion inicial. */
    public static final String ASSIGNMENT_PERMISSION_CODE = "INVENTORY_REGULARIZATION_ASSIGNMENT_PERMISSION";
    /** Un bloqueo no se obtuvo a tiempo (o hubo un deadlock): nada se aplico, la solicitud puede repetirse. */
    public static final String BUSY_CODE = "INVENTORY_REGULARIZATION_BUSY";

    /** Permisos que exige la asignacion inicial: ejecutar ajustes y modificar la configuracion del producto. */
    static final String ADJUSTMENT_PERMISSION = "inventory.adjustment.create";
    static final String PRODUCT_UPDATE_PERMISSION = "catalog.products.update";

    /**
     * Espera maxima por un bloqueo (PostgreSQL {@code lock_timeout}). Solo acota las esperas: la ausencia de
     * ciclos la da el orden de bloqueos y el modo del bloqueo del producto, no este valor.
     */
    private static final String LOCK_TIMEOUT = "5000ms";
    private static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";
    private static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";

    private static final Set<InventorySerialStatus> OPEN_SERIAL_STATUSES = EnumSet.of(
            InventorySerialStatus.AVAILABLE, InventorySerialStatus.RESERVED, InventorySerialStatus.IN_TRANSIT);

    private final CurrentUser currentUser;
    private final PermissionResolver permissionResolver;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final ProductInventorySettingsRepository settingsRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryLotBalanceRepository lotBalanceRepository;
    private final InventorySerialRepository serialRepository;
    private final InventoryMovementRepository movementRepository;
    private final InventoryMovementTraceRepository movementTraceRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryBalanceRegularizationRepository regularizationRepository;
    private final InventoryOperationalLocationService operationalLocations;
    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;

    // ------------------------------------------------------------------ vista previa

    /** Vista previa sin asignacion inicial (comportamiento original). */
    @Transactional(readOnly = true)
    public LegacyBalanceRegularizationPreviewResponse preview(
            UUID branchId, UUID productId, UUID locationId) {
        return preview(branchId, productId, locationId, false);
    }

    /**
     * Vista previa de solo lectura. Con {@code assign = true} evalua la asignacion inicial del destino: el
     * producto sin ubicacion asignada deja de reportar {@code DESTINATION_NOT_ASSIGNED} y aparece, si el
     * usuario no puede asignarla, el bloqueo {@code ASSIGNMENT_PERMISSION}. La huella incluye la asignacion
     * vigente y el modo, de modo que una vista previa solo sirve para ejecutar en su mismo modo.
     */
    @Transactional(readOnly = true)
    public LegacyBalanceRegularizationPreviewResponse preview(
            UUID branchId, UUID productId, UUID locationId, boolean assign) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        Scope scope = requireScope(actor, branchId, productId, locationId);

        boolean enabled = operationalLocations.locationsEnabled(tenantId);
        UUID assigned = operationalLocations.assignedLocation(tenantId, branchId, productId);
        boolean canAssign = canAssign(actor);
        List<Blocker> blockers = new ArrayList<>(policyBlockers(enabled, assigned, scope, assign));
        if (assign && enabled && assigned == null && !canAssign) {
            blockers.add(new Blocker(
                    ASSIGNMENT_PERMISSION_CODE,
                    "Asignar la ubicación inicial exige los permisos de ajustes de inventario y de "
                            + "actualización de productos."));
        }
        boolean assignmentRequired = assigned == null;
        boolean assignmentAllowed = assignmentRequired
                && canAssign
                && policyBlockers(enabled, assigned, scope, true).isEmpty();
        State state = loadState(tenantId, scope, null);
        Analysis analysis = analyze(tenantId, scope, state);
        blockers.addAll(analysis.blockers());

        BigDecimal sourceQuantity = quantity(state.source());
        BigDecimal sourceReserved = reserved(state.source());
        BigDecimal destinationQuantity = quantity(state.destination());
        BigDecimal destinationReserved = reserved(state.destination());
        return new LegacyBalanceRegularizationPreviewResponse(
                branchId,
                productId,
                scope.product().getName(),
                scope.product().getSku(),
                locationId,
                scope.location().getName(),
                blockers.isEmpty(),
                List.copyOf(blockers),
                sourceQuantity,
                sourceReserved,
                destinationQuantity,
                destinationReserved,
                destinationQuantity.add(sourceQuantity),
                destinationReserved.add(sourceReserved),
                state.reservations().size(),
                analysis.emptyAllocations(),
                state.sourceLots().size(),
                state.sourceSerials().size(),
                snapshotFingerprint(state, assigned, assign),
                assigned,
                assignmentRequired,
                assignmentAllowed);
    }

    // ------------------------------------------------------------------ opciones de destino

    /**
     * Ubicacion asignada y ubicaciones activas de la sucursal que podrian asignarse como destino. Solo
     * lectura, con la misma autorizacion de tenant, capacidad y sucursal que la vista previa; nunca devuelve
     * ubicaciones de otra sucursal.
     */
    @Transactional(readOnly = true)
    public RegularizationOptionsResponse options(UUID branchId, UUID productId) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        Product product = requireBranchAndProduct(actor, branchId, productId);

        boolean enabled = operationalLocations.locationsEnabled(tenantId);
        UUID assignedId = operationalLocations.assignedLocation(tenantId, branchId, productId);
        LocationOption assigned = assignedId == null
                ? null
                : locationRepository.findByTenantIdAndId(tenantId, assignedId)
                        .filter(location -> branchId.equals(location.getBranchId()))
                        .map(InventoryBalanceRegularizationService::toOption)
                        .orElse(null);
        List<LocationOption> assignable = enabled
                ? locationRepository
                        .findByTenantIdAndBranchIdAndStatusOrderByCodeAsc(
                                tenantId, branchId, LocationStatus.active)
                        .stream()
                        .map(InventoryBalanceRegularizationService::toOption)
                        .toList()
                : List.of();
        return new RegularizationOptionsResponse(
                branchId,
                productId,
                product.getName(),
                product.getSku(),
                enabled,
                assignedId,
                assigned,
                assignable);
    }

    private static LocationOption toOption(Location location) {
        return new LocationOption(
                location.getId(), location.getCode(), location.getName(), location.getStatus().name());
    }

    // ------------------------------------------------------------------ ejecucion

    /**
     * Ejecuta la regularizacion (y, con {@code assignDestination}, la asignacion inicial) en una transaccion.
     * Una espera de bloqueo vencida o un deadlock se informan como {@value #BUSY_CODE} (409, nada aplicado y
     * reintentable); cualquier otro error, incluidos los de integridad, se propaga sin transformar.
     */
    @Transactional
    public LegacyBalanceRegularizationResultResponse regularize(RegularizeLegacyBalanceRequest request) {
        try {
            return doRegularize(request);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (isLockWaitFailure(exception)) {
                log.warn("Regularizacion sin bloqueo disponible: {}", exception.toString());
                throw BusinessException.conflict(
                        BUSY_CODE,
                        "El producto está siendo modificado por otra operación. No se aplicó ningún cambio; "
                                + "reintente en unos segundos.");
            }
            throw exception;
        }
    }

    private LegacyBalanceRegularizationResultResponse doRegularize(RegularizeLegacyBalanceRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        Scope scope = requireScope(actor, request.branchId(), request.productId(), request.locationId());
        String requestFingerprint = requestFingerprint(tenantId, request);

        LegacyBalanceRegularizationResultResponse replay = replayIfRecorded(
                actor, request.idempotencyKey(), requestFingerprint);
        if (replay != null) {
            return replay;
        }

        UUID branchId = request.branchId();
        UUID productId = request.productId();
        UUID destinationLocationId = request.locationId();
        boolean assignMode = request.assigning();

        // Acota toda espera de bloqueo de esta transaccion (no sustituye al orden de bloqueos).
        applyLockTimeout();

        // 1) configuracion y producto. Normal: producto FOR SHARE. Asignacion inicial: FOR NO KEY UPDATE, que
        // excluye a las decisiones concurrentes y sigue sin chocar con el KEY SHARE de los movimientos. La
        // lectura previa solo elige el modo; la decision se toma con el bloqueo ya tomado.
        UUID assignedHint = currentAssignment(tenantId, branchId, productId);
        boolean exclusive = assignMode && assignedHint == null;
        if (exclusive) {
            requireAssignmentPermissions(actor);
        }
        boolean enabled = exclusive
                ? operationalLocations.lockAssignmentScope(tenantId, productId)
                : operationalLocations.lockDecisionScope(tenantId, productId);
        UUID assigned = currentAssignment(tenantId, branchId, productId);
        if (assignMode && assigned == null && !exclusive) {
            // Alguien limpio la asignacion entre la lectura y el bloqueo compartido: asignar ahora exigiria el
            // bloqueo exclusivo y la vista previa ya no describe el estado.
            throw BusinessException.conflict(
                    STALE_SNAPSHOT_CODE,
                    "La asignación del producto cambió desde la vista previa. Vuelva a cargarla.");
        }
        throwFirst(policyBlockers(enabled, assigned, scope, assignMode));
        boolean assigning = assignMode && assigned == null;

        // 2) balances agregados NULL y destino, por id. Cada fila se bloquea individualmente y se refresca.
        UUID sourceId = balanceRepository
                .findDefaultBalanceId(tenantId, branchId, productId)
                .orElseThrow(() -> BusinessException.conflict(
                        NOT_REQUIRED_CODE, "El producto no tiene saldo heredado sin ubicación que regularizar."));
        balanceRepository.ensureLocationBalanceExists(tenantId, branchId, productId, destinationLocationId);
        UUID destinationId = balanceRepository
                .findBalanceIdAtLocation(tenantId, branchId, productId, destinationLocationId)
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el balance destino."));
        Map<UUID, InventoryBalance> lockedBalances = new HashMap<>();
        for (UUID balanceId : Stream.of(sourceId, destinationId).sorted().toList()) {
            InventoryBalance balance = balanceRepository
                    .findByTenantIdAndId(tenantId, balanceId)
                    .orElseThrow(() -> BusinessException.conflict(
                            INCONSISTENT_CODE, "El balance cambió durante la regularización."));
            entityManager.refresh(balance);
            lockedBalances.put(balanceId, balance);
        }
        InventoryBalance source = lockedBalances.get(sourceId);
        InventoryBalance destination = lockedBalances.get(destinationId);

        // Una solicitud con la misma clave que esperaba estos bloqueos ve aqui el resultado de la primera
        // (los bloqueos compartidos de arriba no la detienen).
        replay = replayIfRecorded(actor, request.idempotencyKey(), requestFingerprint);
        if (replay != null) {
            return replay;
        }

        // 3) reservas activas del producto: bloqueo individual por id y refresco, como el ciclo de vida.
        List<InventoryReservation> lockedReservations = new ArrayList<>();
        for (InventoryReservation snapshot : reservationRepository.findByScopeAndStatusOrderById(
                tenantId, branchId, productId, InventoryReservationStatus.active)) {
            InventoryReservation reservation = reservationRepository
                    .findByTenantIdAndId(tenantId, snapshot.getId())
                    .orElseThrow(() -> BusinessException.conflict(
                            INCONSISTENT_CODE, "Una reserva cambió durante la regularización."));
            entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
            if (reservation.getStatus() == InventoryReservationStatus.active) {
                lockedReservations.add(reservation);
            }
        }

        // 4) lotes: filas NULL por lote y la fila destino del mismo lote (creada si no existe).
        List<InventoryLotBalance> sourceLots = lotBalanceRepository
                .findAllForUpdateWithoutLocation(tenantId, branchId, productId);
        Map<UUID, InventoryLotBalance> destinationLots = new HashMap<>();
        for (InventoryLotBalance sourceLot : sourceLots) {
            lotBalanceRepository.ensureAtLocationExists(
                    UUID.randomUUID(), tenantId, branchId, destinationLocationId, sourceLot.getLotId());
            destinationLots.put(
                    sourceLot.getLotId(),
                    lotBalanceRepository
                            .findForUpdateAtLocation(
                                    tenantId, branchId, productId, sourceLot.getLotId(), destinationLocationId)
                            .orElseThrow(() -> BusinessException.conflict(
                                    INCONSISTENT_CODE, "El balance de lote destino no está disponible.")));
        }

        // 5) series fisicas del balance NULL.
        List<InventorySerial> sourceSerials = serialRepository.findPhysicalForUpdateWithoutLocation(
                tenantId, branchId, productId, OPEN_SERIAL_STATUSES);

        State state = loadState(
                tenantId,
                scope,
                new LockedPart(source, destination, lockedReservations, sourceLots, sourceSerials));
        Analysis analysis = analyze(tenantId, scope, state);
        throwFirst(analysis.blockers());

        requireSnapshot(request, state, assigned, assignMode);

        // ---- mutacion (todo o nada)
        if (assigning) {
            // Asignacion inicial: condicional en SQL (solo si sigue sin ubicacion) y sin limpiar el contexto de
            // persistencia, para no perder los balances, reservas y series ya bloqueados y modificados.
            int assignedRows = settingsRepository.assignIfUnassigned(
                    tenantId, branchId, productId, destinationLocationId);
            if (assignedRows != 1) {
                throw BusinessException.conflict(
                        ASSIGNMENT_CONFLICT_CODE,
                        "El producto ya tiene una ubicación operativa asignada; no se puede asignar otra.");
            }
        }
        BigDecimal movedQuantity = source.getQuantity();
        BigDecimal movedReserved = source.getReservedQuantity();
        BigDecimal destinationBefore = destination.getQuantity();
        Map<UUID, BigDecimal> movedByLot = new HashMap<>();
        for (InventoryLotBalance sourceLot : sourceLots) {
            movedByLot.put(sourceLot.getLotId(), sourceLot.getQuantity());
        }
        try {
            destination.add(movedQuantity);
            if (movedReserved.signum() > 0) {
                destination.reserve(movedReserved);
                source.releaseReservation(movedReserved);
            }
            source.deduct(movedQuantity);
            for (InventoryLotBalance sourceLot : sourceLots) {
                BigDecimal lotQuantity = sourceLot.getQuantity();
                destinationLots.get(sourceLot.getLotId()).add(lotQuantity);
                sourceLot.deduct(lotQuantity);
            }
        } catch (IllegalStateException exception) {
            throw BusinessException.conflict(
                    INCONSISTENT_CODE, "Las cantidades no permiten consolidar el saldo de forma segura.");
        }
        for (InventorySerial serial : sourceSerials) {
            serial.setLocationId(destinationLocationId);
        }
        for (InventoryReservation reservation : analysis.affected()) {
            reservation.setAllocations(
                    rewriteAllocations(reservation, sourceId, destinationId, destinationLocationId));
        }

        InventoryBalanceRegularization regularization = InventoryBalanceRegularization.builder()
                .branchId(branchId)
                .productId(productId)
                .fromLocationId(null)
                .toLocationId(destinationLocationId)
                .idempotencyKey(request.idempotencyKey())
                .fingerprint(requestFingerprint)
                .reason(request.reason().trim())
                .performedByUserId(actor.userId())
                .movedQuantity(movedQuantity)
                .movedReservedQuantity(movedReserved)
                .destinationQuantityBefore(destinationBefore)
                .destinationQuantityAfter(destination.getQuantity())
                .resultPayload("{}")
                .build();
        regularization.setTenantId(tenantId);
        try {
            regularization = regularizationRepository.saveAndFlush(regularization);
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict(
                    IDEMPOTENCY_KEY_REUSED_CODE,
                    "La clave de idempotencia ya fue utilizada; reintente la operación.");
        }

        InventoryMovement movement = movementRepository.saveAndFlush(InventoryMovement.builder()
                .tenantId(tenantId)
                .branchId(branchId)
                .productId(productId)
                .type(InventoryMovementType.transfer)
                .reason(request.reason().trim())
                .quantity(movedQuantity)
                .quantityBefore(destinationBefore)
                .quantityAfter(destination.getQuantity())
                .fromLocationId(null)
                .toLocationId(destinationLocationId)
                .referenceType(REFERENCE_TYPE)
                .referenceId(regularization.getId())
                .performedByUserId(actor.userId())
                .build());
        saveTraces(tenantId, movement.getId(), scope.product(), movedByLot, sourceSerials);

        LegacyBalanceRegularizationResultResponse result = new LegacyBalanceRegularizationResultResponse(
                regularization.getId(),
                false,
                regularization.getCreatedAt(),
                branchId,
                productId,
                null,
                destinationLocationId,
                movedQuantity,
                movedReserved,
                destinationBefore,
                destination.getQuantity(),
                destination.getReservedQuantity(),
                analysis.affected().size(),
                sourceLots.size(),
                sourceSerials.size(),
                movement.getId(),
                assigning,
                assigned);
        regularization.setMovementId(movement.getId());
        regularization.setResultPayload(jsonMapper.writeValueAsString(result));
        regularizationRepository.saveAndFlush(regularization);
        log.info(
                "Regularizacion de balance heredado: tenant={} sucursal={} producto={} destino={} "
                        + "cantidad={} reservado={} asignacionInicial={}",
                tenantId, branchId, productId, destinationLocationId, movedQuantity, movedReserved, assigning);
        return result;
    }

    // ------------------------------------------------------------------ analisis

    private record Scope(Product product, Location location) {}

    /** Parte del estado ya bloqueada (ejecucion). */
    private record LockedPart(
            InventoryBalance source,
            InventoryBalance destination,
            List<InventoryReservation> reservations,
            List<InventoryLotBalance> sourceLots,
            List<InventorySerial> sourceSerials) {}

    private record State(
            InventoryBalance source,
            InventoryBalance destination,
            List<InventoryBalance> otherBalances,
            List<InventoryLotBalance> sourceLots,
            List<InventoryLotBalance> otherLots,
            List<InventorySerial> sourceSerials,
            List<InventorySerial> otherSerials,
            List<InventoryReservation> reservations) {}

    private record Analysis(
            List<Blocker> blockers, List<InventoryReservation> affected, int emptyAllocations) {}

    private record Entry(UUID balanceId, BigDecimal reserved) {}

    private record Parsed(boolean valid, List<Entry> entries) {
        static Parsed invalid() {
            return new Parsed(false, List.of());
        }
    }

    /**
     * Estado del producto en la sucursal. En la vista previa se lee sin bloqueo; en la ejecucion se usan las
     * entidades ya bloqueadas (las que no se modifican, balances y lotes de terceras ubicaciones, se leen).
     */
    private State loadState(UUID tenantId, Scope scope, LockedPart locked) {
        UUID branchId = scope.location().getBranchId();
        UUID productId = scope.product().getId();
        UUID destinationLocationId = scope.location().getId();

        List<InventoryBalance> balances = balanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId);
        InventoryBalance source = locked != null
                ? locked.source()
                : balances.stream().filter(balance -> balance.getLocationId() == null).findFirst().orElse(null);
        InventoryBalance destination = locked != null
                ? locked.destination()
                : balances.stream()
                        .filter(balance -> destinationLocationId.equals(balance.getLocationId()))
                        .findFirst()
                        .orElse(null);
        List<InventoryBalance> others = balances.stream()
                .filter(balance -> balance.getLocationId() != null
                        && !destinationLocationId.equals(balance.getLocationId()))
                .filter(balance -> balance.getQuantity().signum() > 0
                        || balance.getReservedQuantity().signum() > 0)
                .toList();

        List<InventoryLotBalance> allLots = lotBalanceRepository
                .findByTenantBranchAndProduct(tenantId, branchId, productId);
        List<InventoryLotBalance> sourceLots = locked != null
                ? locked.sourceLots()
                : allLots.stream()
                        .filter(lot -> lot.getLocationId() == null)
                        .filter(lot -> lot.getQuantity().signum() > 0 || lot.getReservedQuantity().signum() > 0)
                        .sorted(Comparator.comparing(InventoryLotBalance::getLotId))
                        .toList();
        List<InventoryLotBalance> otherLots = allLots.stream()
                .filter(lot -> lot.getLocationId() != null
                        && !destinationLocationId.equals(lot.getLocationId()))
                .filter(lot -> lot.getQuantity().signum() > 0 || lot.getReservedQuantity().signum() > 0)
                .toList();

        List<InventorySerial> allSerials = serialRepository
                .findByTenantIdAndBranchIdAndProductIdOrderBySerialNumberAsc(tenantId, branchId, productId);
        List<InventorySerial> sourceSerials = locked != null
                ? locked.sourceSerials()
                : allSerials.stream()
                        .filter(serial -> serial.getLocationId() == null)
                        .filter(serial -> OPEN_SERIAL_STATUSES.contains(serial.getStatus()))
                        .toList();
        List<InventorySerial> otherSerials = allSerials.stream()
                .filter(serial -> serial.getLocationId() != null
                        && !destinationLocationId.equals(serial.getLocationId()))
                .filter(serial -> OPEN_SERIAL_STATUSES.contains(serial.getStatus()))
                .toList();

        List<InventoryReservation> reservations = locked != null
                ? locked.reservations()
                : reservationRepository.findByScopeAndStatusOrderById(
                        tenantId, branchId, productId, InventoryReservationStatus.active);
        return new State(
                source, destination, others, sourceLots, otherLots, sourceSerials, otherSerials, reservations);
    }

    /**
     * Politica de destino. Sin asignacion y en modo {@code assign} el destino puede asignarse en la misma
     * operacion; con otra ubicacion ya asignada nunca se cambia (ni siquiera en modo {@code assign}).
     */
    private static List<Blocker> policyBlockers(
            boolean enabled, UUID assigned, Scope scope, boolean assign) {
        List<Blocker> blockers = new ArrayList<>();
        if (!enabled) {
            blockers.add(new Blocker(
                    LOCATIONS_DISABLED_CODE,
                    "El control de ubicaciones está deshabilitado: el balance sin ubicación es el operativo."));
            return blockers;
        }
        if (assigned == null) {
            if (!assign) {
                blockers.add(new Blocker(
                        DESTINATION_NOT_ASSIGNED_CODE,
                        "El producto no tiene ubicación operativa asignada en la sucursal; asigne el destino "
                                + "para regularizar."));
            }
        } else if (!assigned.equals(scope.location().getId())) {
            blockers.add(new Blocker(
                    assign ? ASSIGNMENT_CONFLICT_CODE : DESTINATION_NOT_ASSIGNED_CODE,
                    "La ubicación destino debe ser la ubicación operativa asignada al producto en la "
                            + "sucursal; una asignación existente no se cambia."));
        }
        if (scope.location().getStatus() != LocationStatus.active) {
            blockers.add(new Blocker(
                    DESTINATION_INACTIVE_CODE, "La ubicación destino debe estar activa."));
        }
        return blockers;
    }

    private Analysis analyze(UUID tenantId, Scope scope, State state) {
        List<Blocker> blockers = new ArrayList<>();
        Product product = scope.product();
        InventoryBalance source = state.source();
        boolean hasSourceStock = source != null
                && (source.getQuantity().signum() > 0 || source.getReservedQuantity().signum() > 0);
        if (!hasSourceStock && state.sourceLots().isEmpty() && state.sourceSerials().isEmpty()) {
            blockers.add(new Blocker(
                    NOT_REQUIRED_CODE, "El producto no tiene saldo heredado sin ubicación que regularizar."));
            return new Analysis(blockers, List.of(), 0);
        }
        if (!hasSourceStock) {
            blockers.add(new Blocker(
                    TRACEABILITY_INCONSISTENT_CODE,
                    "Existen lotes o series sin ubicación sin un saldo agregado que los respalde."));
            return new Analysis(blockers, List.of(), 0);
        }

        if (source.getReservedQuantity().compareTo(source.getQuantity()) > 0) {
            blockers.add(new Blocker(
                    INCONSISTENT_CODE, "El balance sin ubicación reserva más de lo que existe."));
            return new Analysis(blockers, List.of(), 0);
        }

        if (!state.otherBalances().isEmpty()
                || !state.otherLots().isEmpty()
                || !state.otherSerials().isEmpty()) {
            blockers.add(new Blocker(
                    THIRD_LOCATION_STOCK_CODE,
                    "El producto conserva existencias en otra ubicación distinta del destino; regularice "
                            + "ese inventario primero."));
        }

        analyzeTraceability(product, state, blockers);

        List<InventoryReservation> affected = new ArrayList<>();
        int emptyAllocations = analyzeReservations(scope, state, blockers, affected);
        analyzePicking(tenantId, scope, affected, blockers);
        return new Analysis(blockers, affected, emptyAllocations);
    }

    private static void analyzeTraceability(Product product, State state, List<Blocker> blockers) {
        boolean lotTracked = Boolean.TRUE.equals(product.getTrackingLot());
        boolean serialTracked = Boolean.TRUE.equals(product.getTrackingSerial());
        InventoryBalance source = state.source();
        List<InventoryLotBalance> lots = state.sourceLots();
        List<InventorySerial> serials = state.sourceSerials();
        if ((!lotTracked && !lots.isEmpty()) || (!serialTracked && !serials.isEmpty())) {
            blockers.add(new Blocker(
                    TRACEABILITY_INCONSISTENT_CODE,
                    "Hay lotes o series sin ubicación en un producto que ya no usa esa trazabilidad."));
            return;
        }
        if (!lotTracked && !serialTracked) {
            return;
        }
        if (source.getReservedQuantity().signum() > 0) {
            blockers.add(new Blocker(
                    TRACEABLE_RESERVATION_CODE,
                    "Un producto trazable con reservas sobre el saldo sin ubicación no puede regularizarse: "
                            + "su reserva física exige una ubicación."));
        }
        if (lots.stream().anyMatch(lot -> lot.getReservedQuantity().signum() > 0)
                || serials.stream().anyMatch(serial -> serial.getStatus() != InventorySerialStatus.AVAILABLE)) {
            blockers.add(new Blocker(
                    TRACEABILITY_INCONSISTENT_CODE,
                    "Hay reservas físicas, series reservadas o en tránsito sobre el saldo sin ubicación."));
            return;
        }
        if (lotTracked) {
            BigDecimal lotQuantity = lots.stream()
                    .map(InventoryLotBalance::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lotQuantity.compareTo(source.getQuantity()) != 0) {
                blockers.add(new Blocker(
                        TRACEABILITY_INCONSISTENT_CODE,
                        "La suma de los lotes no coincide con el saldo agregado sin ubicación."));
                return;
            }
        }
        if (serialTracked) {
            if (BigDecimal.valueOf(serials.size()).compareTo(source.getQuantity()) != 0) {
                blockers.add(new Blocker(
                        TRACEABILITY_INCONSISTENT_CODE,
                        "El número de series no coincide con el saldo agregado sin ubicación."));
                return;
            }
            if (lotTracked) {
                Map<UUID, Long> serialsByLot = serials.stream()
                        .filter(serial -> serial.getLotId() != null)
                        .collect(Collectors.groupingBy(InventorySerial::getLotId, Collectors.counting()));
                boolean loose = serials.stream().anyMatch(serial -> serial.getLotId() == null);
                boolean mismatch = lots.stream().anyMatch(lot -> lot.getQuantity().compareTo(
                        BigDecimal.valueOf(serialsByLot.getOrDefault(lot.getLotId(), 0L))) != 0);
                if (loose || mismatch) {
                    blockers.add(new Blocker(
                            TRACEABILITY_INCONSISTENT_CODE,
                            "Las series sin ubicación no coinciden con los saldos por lote."));
                }
            }
        }
    }

    /** Devuelve cuantas reservas tienen allocations vacios; llena {@code affected} con las que cambian. */
    private int analyzeReservations(
            Scope scope, State state, List<Blocker> blockers, List<InventoryReservation> affected) {
        UUID sourceId = state.source().getId();
        UUID destinationId = state.destination() == null ? null : state.destination().getId();
        BigDecimal reservedOnSource = BigDecimal.ZERO;
        BigDecimal reservedOnDestination = BigDecimal.ZERO;
        int emptyAllocations = 0;
        boolean invalid = false;
        boolean otherBalance = false;
        for (InventoryReservation reservation : state.reservations()) {
            Parsed parsed = parse(reservation);
            if (!parsed.valid()) {
                invalid = true;
                continue;
            }
            if (parsed.entries().isEmpty()) {
                emptyAllocations++;
                reservedOnSource = reservedOnSource.add(reservation.getQuantity());
                affected.add(reservation);
                continue;
            }
            boolean touchesSource = false;
            for (Entry entry : parsed.entries()) {
                if (entry.balanceId().equals(sourceId)) {
                    touchesSource = true;
                    reservedOnSource = reservedOnSource.add(entry.reserved());
                } else if (entry.balanceId().equals(destinationId)) {
                    reservedOnDestination = reservedOnDestination.add(entry.reserved());
                } else {
                    otherBalance = true;
                }
            }
            if (touchesSource) {
                affected.add(reservation);
            }
        }
        if (invalid) {
            blockers.add(new Blocker(
                    RESERVATION_INVALID_CODE,
                    "Hay reservas activas con allocations inválidas, parcialmente consumidas o sin cantidad."));
        }
        if (otherBalance) {
            blockers.add(new Blocker(
                    RESERVATION_OTHER_BALANCE_CODE,
                    "Hay reservas activas asignadas a un balance distinto del heredado y del destino."));
        }
        if (!invalid && !otherBalance) {
            if (reservedOnSource.compareTo(state.source().getReservedQuantity()) != 0) {
                blockers.add(new Blocker(
                        RESERVATION_DRIFT_CODE,
                        "Las reservas activas no explican el reservado del balance sin ubicación."));
            }
            BigDecimal destinationReserved = reserved(state.destination());
            if (reservedOnDestination.compareTo(destinationReserved) != 0) {
                blockers.add(new Blocker(
                        RESERVATION_DRIFT_CODE,
                        "Las reservas activas no explican el reservado del balance destino."));
            }
        }
        return emptyAllocations;
    }

    private Parsed parse(InventoryReservation reservation) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(reservation.getAllocations());
        } catch (JacksonException exception) {
            return Parsed.invalid();
        }
        if (root == null || !root.isArray()) {
            return Parsed.invalid();
        }
        List<Entry> entries = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                return Parsed.invalid();
            }
            JsonNode balance = node.get("balanceId");
            JsonNode reserved = node.get("reservedQuantity");
            JsonNode consumed = node.get("consumedQuantity");
            if (balance == null || balance.isNull() || reserved == null || reserved.isNull()) {
                return Parsed.invalid();
            }
            try {
                UUID balanceId = UUID.fromString(balance.asText());
                BigDecimal reservedQuantity = new BigDecimal(reserved.asText());
                BigDecimal consumedQuantity = consumed == null || consumed.isNull()
                        ? BigDecimal.ZERO
                        : new BigDecimal(consumed.asText());
                if (reservedQuantity.signum() <= 0 || consumedQuantity.signum() != 0) {
                    return Parsed.invalid();
                }
                entries.add(new Entry(balanceId, reservedQuantity));
                total = total.add(reservedQuantity);
            } catch (IllegalArgumentException exception) {
                return Parsed.invalid();
            }
        }
        if (!entries.isEmpty() && total.compareTo(reservation.getQuantity()) != 0) {
            return Parsed.invalid();
        }
        return new Parsed(true, List.copyOf(entries));
    }

    private void analyzePicking(
            UUID tenantId, Scope scope, List<InventoryReservation> affected, List<Blocker> blockers) {
        if (affected.isEmpty()) {
            return;
        }
        UUID branchId = scope.location().getBranchId();
        List<Object[]> rows = entityManager.createQuery(
                        "select item, picking from PickingItem item, PickingOrder picking "
                                + "where item.pickingOrderId = picking.id "
                                + "and item.tenantId = :tenantId and picking.tenantId = :tenantId "
                                + "and picking.branchId = :branchId and item.productId = :productId "
                                + "and picking.status <> :cancelled",
                        Object[].class)
                .setParameter("tenantId", tenantId)
                .setParameter("branchId", branchId)
                .setParameter("productId", scope.product().getId())
                .setParameter("cancelled", PickingStatus.cancelled)
                .getResultList();
        UUID destination = scope.location().getId();
        boolean physical = false;
        boolean location = false;
        for (InventoryReservation reservation : affected) {
            for (Object[] row : rows) {
                PickingItem item = (PickingItem) row[0];
                PickingOrder picking = (PickingOrder) row[1];
                if (!item.getSourceLineId().equals(reservation.getSourceLineId())
                        || !picking.getSourceId().equals(reservation.getSourceId())
                        || !picking.getSourceType().name().equals(reservation.getSourceType().name())) {
                    continue;
                }
                if (hasPhysicalSelection(item.getPickedTraces())) {
                    physical = true;
                }
                if (item.getLocationId() != null && !destination.equals(item.getLocationId())) {
                    location = true;
                }
            }
        }
        if (physical) {
            blockers.add(new Blocker(
                    PICKING_CONFLICT_CODE,
                    "Un Picking en curso tiene selección física asociada a reservas del saldo sin ubicación."));
        }
        if (location) {
            blockers.add(new Blocker(
                    PICKING_CONFLICT_CODE,
                    "Un Picking en curso tiene una ubicación distinta del destino para reservas del saldo "
                            + "sin ubicación."));
        }
    }

    private static boolean hasPhysicalSelection(String pickedTraces) {
        if (pickedTraces == null) {
            return false;
        }
        String trimmed = pickedTraces.trim();
        return !trimmed.isEmpty() && !"[]".equals(trimmed) && !"{}".equals(trimmed) && !"null".equals(trimmed);
    }

    // ------------------------------------------------------------------ escritura

    private String rewriteAllocations(
            InventoryReservation reservation, UUID sourceId, UUID destinationId, UUID destinationLocationId) {
        JsonNode root = jsonMapper.readTree(reservation.getAllocations());
        ArrayNode result = jsonMapper.createArrayNode();
        if (root.isEmpty()) {
            ObjectNode entry = jsonMapper.createObjectNode();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("balanceId", destinationId.toString());
            entry.put("locationId", destinationLocationId.toString());
            entry.put("reservedQuantity", reservation.getQuantity());
            entry.put("consumedQuantity", BigDecimal.ZERO.setScale(3));
            result.add(entry);
        } else {
            for (JsonNode node : root) {
                ObjectNode copy = ((ObjectNode) node).deepCopy();
                if (sourceId.toString().equals(copy.get("balanceId").asText())) {
                    copy.put("balanceId", destinationId.toString());
                    copy.put("locationId", destinationLocationId.toString());
                }
                result.add(copy);
            }
        }
        return jsonMapper.writeValueAsString(result);
    }

    /**
     * Trazas del movimiento: una por serie para productos serializados (el lote queda implicito en la serie)
     * o una por lote con la cantidad movida. Los productos sin trazabilidad no generan trazas.
     */
    private void saveTraces(
            UUID tenantId,
            UUID movementId,
            Product product,
            Map<UUID, BigDecimal> movedByLot,
            List<InventorySerial> serials) {
        List<InventoryMovementTrace> traces = new ArrayList<>();
        if (Boolean.TRUE.equals(product.getTrackingSerial())) {
            for (InventorySerial serial : serials) {
                traces.add(InventoryMovementTrace.builder()
                        .tenantId(tenantId)
                        .movementId(movementId)
                        .serialId(serial.getId())
                        .quantity(BigDecimal.ONE)
                        .build());
            }
        } else if (Boolean.TRUE.equals(product.getTrackingLot())) {
            movedByLot.entrySet().stream()
                    .filter(entry -> entry.getValue().signum() > 0)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> traces.add(InventoryMovementTrace.builder()
                            .tenantId(tenantId)
                            .movementId(movementId)
                            .lotId(entry.getKey())
                            .quantity(entry.getValue())
                            .build()));
        }
        if (!traces.isEmpty()) {
            movementTraceRepository.saveAllAndFlush(traces);
        }
    }

    // ------------------------------------------------------------------ precondiciones e idempotencia

    private void requireSnapshot(
            RegularizeLegacyBalanceRequest request, State state, UUID assigned, boolean assignMode) {
        boolean quantitiesMatch =
                request.expectedSourceQuantity().compareTo(quantity(state.source())) == 0
                        && request.expectedSourceReservedQuantity().compareTo(reserved(state.source())) == 0
                        && request.expectedDestinationQuantity().compareTo(quantity(state.destination())) == 0;
        if (!quantitiesMatch
                || !request.snapshotFingerprint().equals(snapshotFingerprint(state, assigned, assignMode))) {
            throw BusinessException.conflict(
                    STALE_SNAPSHOT_CODE,
                    "El inventario cambió desde la vista previa. Vuelva a cargar la vista previa.");
        }
    }

    private LegacyBalanceRegularizationResultResponse replayIfRecorded(
            AuthenticatedUser actor, UUID idempotencyKey, String requestFingerprint) {
        return regularizationRepository
                .findByTenantIdAndIdempotencyKey(actor.tenantId(), idempotencyKey)
                .map(recorded -> {
                    if (!recorded.getFingerprint().equals(requestFingerprint)) {
                        throw BusinessException.conflict(
                                IDEMPOTENCY_KEY_REUSED_CODE,
                                "La clave de idempotencia ya fue utilizada con una solicitud diferente.");
                    }
                    LegacyBalanceRegularizationResultResponse result = jsonMapper.readValue(
                            recorded.getResultPayload(), LegacyBalanceRegularizationResultResponse.class);
                    if (result.assignmentApplied()) {
                        // Reproducir una asignacion inicial exige los mismos permisos que ejecutarla.
                        requireAssignmentPermissions(actor);
                    }
                    return result.asReplay();
                })
                .orElse(null);
    }

    /**
     * Huella de la solicitud. El modo de asignacion solo se agrega cuando esta activo: las solicitudes sin
     * asignacion (todas las anteriores a este modo) conservan exactamente su huella, de modo que su replay
     * sigue funcionando.
     */
    private static String requestFingerprint(UUID tenantId, RegularizeLegacyBalanceRequest request) {
        List<String> parts = new ArrayList<>(List.of(
                tenantId.toString(),
                request.branchId().toString(),
                request.productId().toString(),
                request.locationId().toString(),
                request.reason().trim(),
                plain(request.expectedSourceQuantity()),
                plain(request.expectedSourceReservedQuantity()),
                plain(request.expectedDestinationQuantity()),
                request.snapshotFingerprint()));
        if (request.assigning()) {
            parts.add("assignDestination");
        }
        return sha256(String.join("|", parts));
    }

    /**
     * Huella canonica del estado que la operacion movera; la misma en la vista previa y en la ejecucion.
     * Incluye la asignacion vigente del producto y el modo de operacion: si alguien asigna o limpia la
     * ubicacion entre la vista previa y la ejecucion, o se cambia de modo, la solicitud queda obsoleta.
     */
    private static String snapshotFingerprint(State state, UUID assigned, boolean assignMode) {
        List<String> parts = new ArrayList<>();
        parts.add("A|" + (assigned == null ? "-" : assigned.toString()) + "|" + (assignMode ? "assign" : "normal"));
        parts.add("S|" + plain(quantity(state.source())) + "|" + plain(reserved(state.source())));
        // Un destino sin fila es equivalente a una fila en 0/0: la ejecucion la crea (ensure) antes de
        // calcular la huella, asi que ambos estados deben producir el mismo valor.
        parts.add("D|" + plain(quantity(state.destination())) + "|" + plain(reserved(state.destination())));
        state.sourceLots().stream()
                .sorted(Comparator.comparing(InventoryLotBalance::getLotId))
                .forEach(lot -> parts.add("L|" + lot.getLotId() + "|" + plain(lot.getQuantity())
                        + "|" + plain(lot.getReservedQuantity())));
        state.sourceSerials().stream()
                .sorted(Comparator.comparing(InventorySerial::getSerialNumber))
                .forEach(serial -> parts.add("N|" + serial.getSerialNumber() + "|" + serial.getStatus()
                        + "|" + serial.getLotId()));
        state.reservations().stream()
                .sorted(Comparator.comparing(InventoryReservation::getId))
                .forEach(reservation -> parts.add("V|" + reservation.getId() + "|"
                        + plain(reservation.getQuantity()) + "|" + reservation.getAllocations()));
        return sha256(String.join("\n", parts));
    }

    private static String plain(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ------------------------------------------------------------------ alcance y utilidades

    /** Sucursal del tenant con acceso del usuario y producto fisico con control de inventario. */
    private Product requireBranchAndProduct(AuthenticatedUser actor, UUID branchId, UUID productId) {
        UUID tenantId = actor.tenantId();
        branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (!branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        if (product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    PRODUCT_NOT_ELIGIBLE_CODE,
                    "Solo un producto físico con control de inventario puede regularizarse.");
        }
        return product;
    }

    /** Ubicacion asignada leida como escalar (siempre fresca, aunque la entidad ya este cargada). */
    private UUID currentAssignment(UUID tenantId, UUID branchId, UUID productId) {
        return settingsRepository
                .findDefaultLocationId(tenantId, branchId, productId)
                .orElse(null);
    }

    /** La asignacion inicial exige ajustar inventario Y modificar productos; sin rol no hay permisos. */
    private boolean canAssign(AuthenticatedUser actor) {
        return actor.roleId() != null
                && permissionResolver.hasPermission(actor.tenantId(), actor.roleId(), ADJUSTMENT_PERMISSION)
                && permissionResolver.hasPermission(
                        actor.tenantId(), actor.roleId(), PRODUCT_UPDATE_PERMISSION);
    }

    private void requireAssignmentPermissions(AuthenticatedUser actor) {
        if (!canAssign(actor)) {
            throw BusinessException.forbidden(
                    "ACCESS_DENIED",
                    "Asignar la ubicación inicial exige los permisos de ajustes de inventario y de "
                            + "actualización de productos.");
        }
    }

    /** {@code SET LOCAL lock_timeout}: vale hasta el fin de la transaccion y no afecta a otras sesiones. */
    private void applyLockTimeout() {
        entityManager
                .createNativeQuery("SELECT set_config('lock_timeout', :value, true)")
                .setParameter("value", LOCK_TIMEOUT)
                .getSingleResult();
    }

    /**
     * true solo si el fallo es una espera de bloqueo vencida (55P03, incluido el lock_timeout) o un deadlock
     * (40P01), por SQLSTATE o por los tipos de excepcion de JPA y Spring. Un error de integridad (23xxx),
     * de formato u otro no coincide y se propaga sin transformar.
     */
    static boolean isLockWaitFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof PessimisticLockingFailureException
                    || current instanceof PessimisticLockException
                    || current instanceof LockTimeoutException) {
                return true;
            }
            if (current instanceof SQLException sql
                    && (SQLSTATE_LOCK_NOT_AVAILABLE.equals(sql.getSQLState())
                            || SQLSTATE_DEADLOCK_DETECTED.equals(sql.getSQLState()))) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private Scope requireScope(
            AuthenticatedUser actor, UUID branchId, UUID productId, UUID locationId) {
        UUID tenantId = actor.tenantId();
        Product product = requireBranchAndProduct(actor, branchId, productId);
        Location location = locationRepository.findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "Ubicación no encontrada."));
        if (!branchId.equals(location.getBranchId())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_BRANCH_MISMATCH",
                    "La ubicación no pertenece a la sucursal indicada.");
        }
        return new Scope(product, location);
    }

    private static void throwFirst(List<Blocker> blockers) {
        if (!blockers.isEmpty()) {
            Blocker first = blockers.getFirst();
            throw BusinessException.conflict(first.code(), first.message());
        }
    }

    private static BigDecimal quantity(InventoryBalance balance) {
        return balance == null ? BigDecimal.ZERO : balance.getQuantity();
    }

    private static BigDecimal reserved(InventoryBalance balance) {
        return balance == null ? BigDecimal.ZERO : balance.getReservedQuantity();
    }
}
