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
import jakarta.persistence.EntityNotFoundException;
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
import java.util.Objects;
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

    private static final String NOT_REQUIRED_MESSAGE =
            "El producto no tiene saldo heredado sin ubicación que regularizar.";
    private static final String PRODUCT_NOT_FOUND_CODE = "PRODUCT_NOT_FOUND";
    private static final String PRODUCT_NOT_FOUND_MESSAGE = "Producto no encontrado.";
    private static final String LOCATION_NOT_FOUND_CODE = "LOCATION_NOT_FOUND";
    private static final String LOCATION_NOT_FOUND_MESSAGE = "Ubicación no encontrada.";

    /** Nombres de los campos de cada asignacion en {@code inventory_reservations.allocations}. */
    private static final String FIELD_BALANCE_ID = "balanceId";
    private static final String FIELD_LOCATION_ID = "locationId";
    private static final String FIELD_RESERVED_QUANTITY = "reservedQuantity";
    private static final String FIELD_CONSUMED_QUANTITY = "consumedQuantity";

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
        return buildPreview(branchId, productId, locationId, false);
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
        return buildPreview(branchId, productId, locationId, assign);
    }

    /** Cuerpo comun de ambas firmas publicas: asi ninguna invoca a la otra a traves de this. */
    private LegacyBalanceRegularizationPreviewResponse buildPreview(
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

        // 1) configuracion y producto (FOR SHARE, o FOR NO KEY UPDATE en la asignacion inicial). Tras el
        // bloqueo: replay, estado vigente del producto y de la ubicacion, y politica de destino.
        ProductLock productLock = lockProduct(actor, request, scope, requestFingerprint);
        if (productLock.replay() != null) {
            return productLock.replay();
        }
        UUID assigned = productLock.assigned();
        boolean assigning = productLock.assigning();

        // 2) balances agregados NULL y destino, por id. Cada fila se bloquea individualmente y se refresca.
        LockedBalances balances = lockBalances(tenantId, branchId, productId, destinationLocationId);

        // Una solicitud con la misma clave que esperaba estos bloqueos ve aqui el resultado de la primera
        // (en modo normal los bloqueos compartidos de arriba no la detienen).
        replay = replayIfRecorded(actor, request.idempotencyKey(), requestFingerprint);
        if (replay != null) {
            return replay;
        }

        // 3) reservas activas, 4) lotes y 5) series fisicas del balance NULL, en ese orden.
        List<InventoryReservation> lockedReservations = lockActiveReservations(tenantId, branchId, productId);
        LockedLots lots = lockLots(tenantId, branchId, productId, destinationLocationId);
        List<InventorySerial> sourceSerials = serialRepository.findPhysicalForUpdateWithoutLocation(
                tenantId, branchId, productId, OPEN_SERIAL_STATUSES);

        State state = loadState(
                tenantId,
                scope,
                new LockedPart(
                        balances.source(), balances.destination(), lockedReservations, lots.source(),
                        sourceSerials));
        Analysis analysis = analyze(tenantId, scope, state);
        throwFirst(analysis.blockers());

        requireSnapshot(request, state, assigned, assignMode);

        // ---- mutacion (se aplica completa o se revierte completa)
        if (assigning) {
            assignInitialLocation(tenantId, branchId, productId, destinationLocationId);
        }
        Consolidation consolidation = consolidate(
                balances, lots, sourceSerials, analysis.affected(), destinationLocationId);
        return recordOperation(
                new OperationContext(actor, request, requestFingerprint, scope), productLock, consolidation);
    }

    /** Resultado de tomar el producto: replay ya registrado, o la asignacion vigente y si se asignara ahora. */
    private record ProductLock(
            UUID assigned, boolean assigning, LegacyBalanceRegularizationResultResponse replay) {}

    private record OperationContext(
            AuthenticatedUser actor,
            RegularizeLegacyBalanceRequest request,
            String requestFingerprint,
            Scope scope) {}

    private record LockedBalances(
            InventoryBalance source, InventoryBalance destination, UUID sourceId, UUID destinationId) {}

    private record LockedLots(
            List<InventoryLotBalance> source, Map<UUID, InventoryLotBalance> destinationByLot) {}

    /** Lo que movio la consolidacion (valores ya finales) para auditoria y resultado. */
    private record Consolidation(
            BigDecimal movedQuantity,
            BigDecimal movedReserved,
            BigDecimal destinationBefore,
            BigDecimal destinationAfter,
            BigDecimal destinationReservedAfter,
            Map<UUID, BigDecimal> movedByLot,
            List<InventorySerial> serials,
            int reservationsReassigned,
            int lotsMerged) {}

    /**
     * Toma configuracion y producto. Normal: producto {@code FOR SHARE}. Asignacion inicial (modo
     * {@code assign} y sin ubicacion asignada): {@code FOR NO KEY UPDATE}, que excluye a las decisiones
     * concurrentes sin chocar con el KEY SHARE de los movimientos; la lectura previa solo elige el modo, la
     * decision se toma con el bloqueo ya tomado. Orden tras el bloqueo: replay (una solicitud con la misma
     * clave que esperaba este bloqueo ve el resultado ya registrado, o {@code KEY_REUSED} si es otra), estado
     * vigente de producto y ubicacion, asignacion vigente y politica de destino.
     */
    private ProductLock lockProduct(
            AuthenticatedUser actor,
            RegularizeLegacyBalanceRequest request,
            Scope scope,
            String requestFingerprint) {
        UUID tenantId = actor.tenantId();
        UUID branchId = request.branchId();
        UUID productId = request.productId();
        boolean assignMode = request.assigning();
        boolean exclusive = assignMode && currentAssignment(tenantId, branchId, productId) == null;
        if (exclusive) {
            requireAssignmentPermissions(actor);
        }
        boolean enabled = exclusive
                ? operationalLocations.lockAssignmentScope(tenantId, productId)
                : operationalLocations.lockDecisionScope(tenantId, productId);

        LegacyBalanceRegularizationResultResponse replay =
                replayIfRecorded(actor, request.idempotencyKey(), requestFingerprint);
        if (replay != null) {
            return new ProductLock(null, false, replay);
        }
        refreshLockedScope(scope, branchId);

        UUID assigned = currentAssignment(tenantId, branchId, productId);
        if (assignMode && assigned == null && !exclusive) {
            // Alguien limpio la asignacion entre la lectura y el bloqueo compartido: asignar ahora exigiria el
            // bloqueo exclusivo y la vista previa ya no describe el estado.
            throw BusinessException.conflict(
                    STALE_SNAPSHOT_CODE,
                    "La asignación del producto cambió desde la vista previa. Vuelva a cargarla.");
        }
        throwFirst(policyBlockers(enabled, assigned, scope, assignMode));
        return new ProductLock(assigned, assignMode && assigned == null, null);
    }

    /**
     * Con el producto bloqueado, vuelve a leer producto y ubicacion (las instancias gestionadas se cargaron
     * antes de esperar) y revalida elegibilidad y pertenencia: un producto que dejo de ser fisico o de
     * controlar stock, o cuya trazabilidad cambio mientras se esperaba, no se regulariza con datos previos.
     * El estado de la ubicacion sigue sin bloqueo propio: la carrera con su inactivacion es preexistente.
     */
    private void refreshLockedScope(Scope scope, UUID branchId) {
        try {
            entityManager.refresh(scope.product());
        } catch (EntityNotFoundException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, PRODUCT_NOT_FOUND_CODE, PRODUCT_NOT_FOUND_MESSAGE);
        }
        try {
            entityManager.refresh(scope.location());
        } catch (EntityNotFoundException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, LOCATION_NOT_FOUND_CODE, LOCATION_NOT_FOUND_MESSAGE);
        }
        requireEligibleProduct(scope.product());
        requireLocationInBranch(scope.location(), branchId);
    }

    private LockedBalances lockBalances(
            UUID tenantId, UUID branchId, UUID productId, UUID destinationLocationId) {
        UUID sourceId = balanceRepository
                .findDefaultBalanceId(tenantId, branchId, productId)
                .orElseThrow(() -> BusinessException.conflict(NOT_REQUIRED_CODE, NOT_REQUIRED_MESSAGE));
        balanceRepository.ensureLocationBalanceExists(tenantId, branchId, productId, destinationLocationId);
        UUID destinationId = balanceRepository
                .findBalanceIdAtLocation(tenantId, branchId, productId, destinationLocationId)
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el balance destino."));
        Map<UUID, InventoryBalance> locked = new HashMap<>();
        for (UUID balanceId : Stream.of(sourceId, destinationId).sorted().toList()) {
            InventoryBalance balance = balanceRepository
                    .findByTenantIdAndId(tenantId, balanceId)
                    .orElseThrow(() -> BusinessException.conflict(
                            INCONSISTENT_CODE, "El balance cambió durante la regularización."));
            entityManager.refresh(balance);
            locked.put(balanceId, balance);
        }
        return new LockedBalances(locked.get(sourceId), locked.get(destinationId), sourceId, destinationId);
    }

    /** Reservas activas del producto: bloqueo individual por id y refresco, como el ciclo de vida. */
    private List<InventoryReservation> lockActiveReservations(UUID tenantId, UUID branchId, UUID productId) {
        List<InventoryReservation> locked = new ArrayList<>();
        for (InventoryReservation snapshot : reservationRepository.findByScopeAndStatusOrderById(
                tenantId, branchId, productId, InventoryReservationStatus.active)) {
            InventoryReservation reservation = reservationRepository
                    .findByTenantIdAndId(tenantId, snapshot.getId())
                    .orElseThrow(() -> BusinessException.conflict(
                            INCONSISTENT_CODE, "Una reserva cambió durante la regularización."));
            entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
            if (reservation.getStatus() == InventoryReservationStatus.active) {
                locked.add(reservation);
            }
        }
        return locked;
    }

    /** Lotes: filas NULL por lote y la fila destino del mismo lote (creada si no existe). */
    private LockedLots lockLots(UUID tenantId, UUID branchId, UUID productId, UUID destinationLocationId) {
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
        return new LockedLots(sourceLots, destinationLots);
    }

    /**
     * Asignacion inicial: condicional en SQL (solo si sigue sin ubicacion) y sin limpiar el contexto de
     * persistencia, para no perder los balances, reservas y series ya bloqueados y modificados.
     */
    private void assignInitialLocation(
            UUID tenantId, UUID branchId, UUID productId, UUID destinationLocationId) {
        int assignedRows = settingsRepository.assignIfUnassigned(
                tenantId, branchId, productId, destinationLocationId);
        if (assignedRows != 1) {
            throw BusinessException.conflict(
                    ASSIGNMENT_CONFLICT_CODE,
                    "El producto ya tiene una ubicación operativa asignada; no se puede asignar otra.");
        }
    }

    /** Mueve saldo, reservado, lotes y series al destino y reasigna las allocations de las reservas. */
    private Consolidation consolidate(
            LockedBalances balances,
            LockedLots lots,
            List<InventorySerial> sourceSerials,
            List<InventoryReservation> affected,
            UUID destinationLocationId) {
        InventoryBalance source = balances.source();
        InventoryBalance destination = balances.destination();
        BigDecimal movedQuantity = source.getQuantity();
        BigDecimal movedReserved = source.getReservedQuantity();
        BigDecimal destinationBefore = destination.getQuantity();
        Map<UUID, BigDecimal> movedByLot = new HashMap<>();
        for (InventoryLotBalance sourceLot : lots.source()) {
            movedByLot.put(sourceLot.getLotId(), sourceLot.getQuantity());
        }
        try {
            destination.add(movedQuantity);
            if (movedReserved.signum() > 0) {
                destination.reserve(movedReserved);
                source.releaseReservation(movedReserved);
            }
            source.deduct(movedQuantity);
            for (InventoryLotBalance sourceLot : lots.source()) {
                BigDecimal lotQuantity = sourceLot.getQuantity();
                lots.destinationByLot().get(sourceLot.getLotId()).add(lotQuantity);
                sourceLot.deduct(lotQuantity);
            }
        } catch (IllegalStateException exception) {
            throw BusinessException.conflict(
                    INCONSISTENT_CODE, "Las cantidades no permiten consolidar el saldo de forma segura.");
        }
        for (InventorySerial serial : sourceSerials) {
            serial.setLocationId(destinationLocationId);
        }
        for (InventoryReservation reservation : affected) {
            reservation.setAllocations(rewriteAllocations(
                    reservation, balances.sourceId(), balances.destinationId(), destinationLocationId));
        }
        return new Consolidation(
                movedQuantity,
                movedReserved,
                destinationBefore,
                destination.getQuantity(),
                destination.getReservedQuantity(),
                movedByLot,
                sourceSerials,
                affected.size(),
                lots.source().size());
    }

    /** Auditoria, movimiento {@code transfer}, trazas y resultado persistido (idempotencia). */
    private LegacyBalanceRegularizationResultResponse recordOperation(
            OperationContext context, ProductLock productLock, Consolidation done) {
        AuthenticatedUser actor = context.actor();
        RegularizeLegacyBalanceRequest request = context.request();
        UUID tenantId = actor.tenantId();
        UUID branchId = request.branchId();
        UUID productId = request.productId();
        UUID destinationLocationId = request.locationId();

        InventoryBalanceRegularization regularization = InventoryBalanceRegularization.builder()
                .branchId(branchId)
                .productId(productId)
                .fromLocationId(null)
                .toLocationId(destinationLocationId)
                .idempotencyKey(request.idempotencyKey())
                .fingerprint(context.requestFingerprint())
                .reason(request.reason().trim())
                .performedByUserId(actor.userId())
                .movedQuantity(done.movedQuantity())
                .movedReservedQuantity(done.movedReserved())
                .destinationQuantityBefore(done.destinationBefore())
                .destinationQuantityAfter(done.destinationAfter())
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
                .quantity(done.movedQuantity())
                .quantityBefore(done.destinationBefore())
                .quantityAfter(done.destinationAfter())
                .fromLocationId(null)
                .toLocationId(destinationLocationId)
                .referenceType(REFERENCE_TYPE)
                .referenceId(regularization.getId())
                .performedByUserId(actor.userId())
                .build());
        saveTraces(tenantId, movement.getId(), context.scope().product(), done.movedByLot(), done.serials());

        LegacyBalanceRegularizationResultResponse result = new LegacyBalanceRegularizationResultResponse(
                regularization.getId(),
                false,
                regularization.getCreatedAt(),
                branchId,
                productId,
                null,
                destinationLocationId,
                done.movedQuantity(),
                done.movedReserved(),
                done.destinationBefore(),
                done.destinationAfter(),
                done.destinationReservedAfter(),
                done.reservationsReassigned(),
                done.lotsMerged(),
                done.serials().size(),
                movement.getId(),
                productLock.assigning(),
                productLock.assigned());
        regularization.setMovementId(movement.getId());
        regularization.setResultPayload(jsonMapper.writeValueAsString(result));
        regularizationRepository.saveAndFlush(regularization);
        log.info(
                "Regularizacion de balance heredado: tenant={} sucursal={} producto={} destino={} "
                        + "cantidad={} reservado={} asignacionInicial={}",
                tenantId, branchId, productId, destinationLocationId, done.movedQuantity(),
                done.movedReserved(), productLock.assigning());
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
        boolean lockedRun = locked != null;

        List<InventoryBalance> balances = balanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId);
        List<InventoryLotBalance> allLots = lotBalanceRepository
                .findByTenantBranchAndProduct(tenantId, branchId, productId);
        List<InventorySerial> allSerials = serialRepository
                .findByTenantIdAndBranchIdAndProductIdOrderBySerialNumberAsc(tenantId, branchId, productId);
        return new State(
                lockedRun ? locked.source() : balanceAt(balances, null),
                lockedRun ? locked.destination() : balanceAt(balances, destinationLocationId),
                balances.stream()
                        .filter(balance -> isOutside(balance.getLocationId(), destinationLocationId))
                        .filter(balance -> hasStock(balance.getQuantity(), balance.getReservedQuantity()))
                        .toList(),
                lockedRun ? locked.sourceLots() : unlocatedLots(allLots),
                allLots.stream()
                        .filter(lot -> isOutside(lot.getLocationId(), destinationLocationId))
                        .filter(lot -> hasStock(lot.getQuantity(), lot.getReservedQuantity()))
                        .toList(),
                lockedRun ? locked.sourceSerials() : unlocatedSerials(allSerials),
                allSerials.stream()
                        .filter(serial -> isOutside(serial.getLocationId(), destinationLocationId))
                        .filter(serial -> OPEN_SERIAL_STATUSES.contains(serial.getStatus()))
                        .toList(),
                lockedRun
                        ? locked.reservations()
                        : reservationRepository.findByScopeAndStatusOrderById(
                                tenantId, branchId, productId, InventoryReservationStatus.active));
    }

    /** Balance de una ubicacion ({@code null} = el heredado sin ubicacion), o null si no existe. */
    private static InventoryBalance balanceAt(List<InventoryBalance> balances, UUID locationId) {
        return balances.stream()
                .filter(balance -> Objects.equals(balance.getLocationId(), locationId))
                .findFirst()
                .orElse(null);
    }

    /** Fila con ubicacion distinta del destino (la fila sin ubicacion no cuenta como "otra"). */
    private static boolean isOutside(UUID locationId, UUID destinationLocationId) {
        return locationId != null && !destinationLocationId.equals(locationId);
    }

    private static boolean hasStock(BigDecimal quantity, BigDecimal reserved) {
        return quantity.signum() > 0 || reserved.signum() > 0;
    }

    private static List<InventoryLotBalance> unlocatedLots(List<InventoryLotBalance> allLots) {
        return allLots.stream()
                .filter(lot -> lot.getLocationId() == null)
                .filter(lot -> hasStock(lot.getQuantity(), lot.getReservedQuantity()))
                .sorted(Comparator.comparing(InventoryLotBalance::getLotId))
                .toList();
    }

    private static List<InventorySerial> unlocatedSerials(List<InventorySerial> allSerials) {
        return allSerials.stream()
                .filter(serial -> serial.getLocationId() == null)
                .filter(serial -> OPEN_SERIAL_STATUSES.contains(serial.getStatus()))
                .toList();
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
            blockers.add(new Blocker(NOT_REQUIRED_CODE, NOT_REQUIRED_MESSAGE));
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
        int emptyAllocations = analyzeReservations(state, blockers, affected);
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
        String mismatch = traceabilityMismatch(lotTracked, serialTracked, source, lots, serials);
        if (mismatch != null) {
            blockers.add(new Blocker(TRACEABILITY_INCONSISTENT_CODE, mismatch));
        }
    }

    /**
     * Primera incoherencia entre el agregado y su desglose (lotes, series, series por lote), o null si cuadra.
     * El orden de las comprobaciones es el de siempre: lotes, numero de series y series por lote.
     */
    private static String traceabilityMismatch(
            boolean lotTracked,
            boolean serialTracked,
            InventoryBalance source,
            List<InventoryLotBalance> lots,
            List<InventorySerial> serials) {
        if (lotTracked && lotQuantity(lots).compareTo(source.getQuantity()) != 0) {
            return "La suma de los lotes no coincide con el saldo agregado sin ubicación.";
        }
        if (!serialTracked) {
            return null;
        }
        if (BigDecimal.valueOf(serials.size()).compareTo(source.getQuantity()) != 0) {
            return "El número de series no coincide con el saldo agregado sin ubicación.";
        }
        if (lotTracked && !serialsMatchLots(lots, serials)) {
            return "Las series sin ubicación no coinciden con los saldos por lote.";
        }
        return null;
    }

    private static BigDecimal lotQuantity(List<InventoryLotBalance> lots) {
        return lots.stream().map(InventoryLotBalance::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Todas las series tienen lote y cada lote tiene tantas series como cantidad. */
    private static boolean serialsMatchLots(List<InventoryLotBalance> lots, List<InventorySerial> serials) {
        Map<UUID, Long> serialsByLot = serials.stream()
                .filter(serial -> serial.getLotId() != null)
                .collect(Collectors.groupingBy(InventorySerial::getLotId, Collectors.counting()));
        boolean loose = serials.stream().anyMatch(serial -> serial.getLotId() == null);
        boolean mismatch = lots.stream().anyMatch(lot -> lot.getQuantity().compareTo(
                BigDecimal.valueOf(serialsByLot.getOrDefault(lot.getLotId(), 0L))) != 0);
        return !loose && !mismatch;
    }

    /** Devuelve cuantas reservas tienen allocations vacios; llena {@code affected} con las que cambian. */
    private int analyzeReservations(
            State state, List<Blocker> blockers, List<InventoryReservation> affected) {
        UUID sourceId = state.source().getId();
        UUID destinationId = state.destination() == null ? null : state.destination().getId();
        ReservationTally tally = new ReservationTally();
        for (InventoryReservation reservation : state.reservations()) {
            if (accumulate(reservation, sourceId, destinationId, tally)) {
                affected.add(reservation);
            }
        }
        addReservationBlockers(tally, state, blockers);
        return tally.emptyAllocations;
    }

    /** Acumulado de la revision de reservas activas (mutable, de uso local). */
    private static final class ReservationTally {
        private BigDecimal reservedOnSource = BigDecimal.ZERO;
        private BigDecimal reservedOnDestination = BigDecimal.ZERO;
        private int emptyAllocations;
        private boolean invalid;
        private boolean otherBalance;

        /** Suma la asignacion al balance que corresponde; true si era la del balance sin ubicacion. */
        boolean add(Entry entry, UUID sourceId, UUID destinationId) {
            if (entry.balanceId().equals(sourceId)) {
                reservedOnSource = reservedOnSource.add(entry.reserved());
                return true;
            }
            if (entry.balanceId().equals(destinationId)) {
                reservedOnDestination = reservedOnDestination.add(entry.reserved());
            } else {
                otherBalance = true;
            }
            return false;
        }
    }

    /** true si la reserva cambia con la consolidacion: usa el balance sin ubicacion (explicita o vacia). */
    private boolean accumulate(
            InventoryReservation reservation, UUID sourceId, UUID destinationId, ReservationTally tally) {
        Parsed parsed = parse(reservation);
        if (!parsed.valid()) {
            tally.invalid = true;
            return false;
        }
        if (parsed.entries().isEmpty()) {
            tally.emptyAllocations++;
            tally.reservedOnSource = tally.reservedOnSource.add(reservation.getQuantity());
            return true;
        }
        boolean touchesSource = false;
        for (Entry entry : parsed.entries()) {
            // Operador no cortocircuitante: toda asignacion debe sumarse aunque una anterior ya toque el origen.
            touchesSource |= tally.add(entry, sourceId, destinationId);
        }
        return touchesSource;
    }

    private static void addReservationBlockers(
            ReservationTally tally, State state, List<Blocker> blockers) {
        if (tally.invalid) {
            blockers.add(new Blocker(
                    RESERVATION_INVALID_CODE,
                    "Hay reservas activas con allocations inválidas, parcialmente consumidas o sin cantidad."));
        }
        if (tally.otherBalance) {
            blockers.add(new Blocker(
                    RESERVATION_OTHER_BALANCE_CODE,
                    "Hay reservas activas asignadas a un balance distinto del heredado y del destino."));
        }
        if (tally.invalid || tally.otherBalance) {
            return;
        }
        if (tally.reservedOnSource.compareTo(state.source().getReservedQuantity()) != 0) {
            blockers.add(new Blocker(
                    RESERVATION_DRIFT_CODE,
                    "Las reservas activas no explican el reservado del balance sin ubicación."));
        }
        if (tally.reservedOnDestination.compareTo(reserved(state.destination())) != 0) {
            blockers.add(new Blocker(
                    RESERVATION_DRIFT_CODE,
                    "Las reservas activas no explican el reservado del balance destino."));
        }
    }

    private Parsed parse(InventoryReservation reservation) {
        JsonNode root = readAllocations(reservation);
        if (root == null || !root.isArray()) {
            return Parsed.invalid();
        }
        List<Entry> entries = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode node : root) {
            Entry entry = parseEntry(node);
            if (entry == null) {
                return Parsed.invalid();
            }
            entries.add(entry);
            total = total.add(entry.reserved());
        }
        if (!entries.isEmpty() && total.compareTo(reservation.getQuantity()) != 0) {
            return Parsed.invalid();
        }
        return new Parsed(true, List.copyOf(entries));
    }

    /** El JSON de allocations, o null si no es legible. */
    private JsonNode readAllocations(InventoryReservation reservation) {
        try {
            return jsonMapper.readTree(reservation.getAllocations());
        } catch (JacksonException exception) {
            return null;
        }
    }

    /**
     * Una asignacion activa valida: objeto con {@code balanceId} UUID, {@code reservedQuantity} positiva y sin
     * consumo (una reserva activa nunca esta consumida a medias). Null si no cumple.
     */
    private static Entry parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode balance = node.get(FIELD_BALANCE_ID);
        JsonNode reserved = node.get(FIELD_RESERVED_QUANTITY);
        JsonNode consumed = node.get(FIELD_CONSUMED_QUANTITY);
        if (isAbsent(balance) || isAbsent(reserved)) {
            return null;
        }
        try {
            UUID balanceId = UUID.fromString(balance.asText());
            BigDecimal reservedQuantity = new BigDecimal(reserved.asText());
            BigDecimal consumedQuantity =
                    isAbsent(consumed) ? BigDecimal.ZERO : new BigDecimal(consumed.asText());
            if (reservedQuantity.signum() <= 0 || consumedQuantity.signum() != 0) {
                return null;
            }
            return new Entry(balanceId, reservedQuantity);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull();
    }

    private void analyzePicking(
            UUID tenantId, Scope scope, List<InventoryReservation> affected, List<Blocker> blockers) {
        if (affected.isEmpty()) {
            return;
        }
        List<Object[]> rows = entityManager.createQuery(
                        "select item, picking from PickingItem item, PickingOrder picking "
                                + "where item.pickingOrderId = picking.id "
                                + "and item.tenantId = :tenantId and picking.tenantId = :tenantId "
                                + "and picking.branchId = :branchId and item.productId = :productId "
                                + "and picking.status <> :cancelled",
                        Object[].class)
                .setParameter("tenantId", tenantId)
                .setParameter("branchId", scope.location().getBranchId())
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
                if (belongsToReservation(item, picking, reservation)) {
                    physical |= hasPhysicalSelection(item.getPickedTraces());
                    location |= item.getLocationId() != null && !destination.equals(item.getLocationId());
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

    /** La linea de Picking corresponde a la reserva: misma linea, misma fuente y mismo tipo de fuente. */
    private static boolean belongsToReservation(
            PickingItem item, PickingOrder picking, InventoryReservation reservation) {
        return item.getSourceLineId().equals(reservation.getSourceLineId())
                && picking.getSourceId().equals(reservation.getSourceId())
                && picking.getSourceType().name().equals(reservation.getSourceType().name());
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
            entry.put(FIELD_BALANCE_ID, destinationId.toString());
            entry.put(FIELD_LOCATION_ID, destinationLocationId.toString());
            entry.put(FIELD_RESERVED_QUANTITY, reservation.getQuantity());
            entry.put(FIELD_CONSUMED_QUANTITY, BigDecimal.ZERO.setScale(3));
            result.add(entry);
        } else {
            for (JsonNode node : root) {
                ObjectNode copy = ((ObjectNode) node).deepCopy();
                if (sourceId.toString().equals(copy.get(FIELD_BALANCE_ID).asText())) {
                    copy.put(FIELD_BALANCE_ID, destinationId.toString());
                    copy.put(FIELD_LOCATION_ID, destinationLocationId.toString());
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

    /**
     * Forma canonica de una cantidad para las huellas: sin ceros finales y sin notacion cientifica; null se
     * trata como cero. El resultado coincide exactamente con el que ya se persistio en las huellas anteriores.
     */
    private static String plain(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.stripTrailingZeros().toPlainString();
        }
        return value.stripTrailingZeros().toPlainString();
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
                        HttpStatus.NOT_FOUND, PRODUCT_NOT_FOUND_CODE, PRODUCT_NOT_FOUND_MESSAGE));
        requireEligibleProduct(product);
        return product;
    }

    /** Solo productos fisicos con control de inventario se regularizan (400 en otro caso). */
    private static void requireEligibleProduct(Product product) {
        if (product.getProductType() != ProductType.physical
                || !Boolean.TRUE.equals(product.getTrackingStock())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    PRODUCT_NOT_ELIGIBLE_CODE,
                    "Solo un producto físico con control de inventario puede regularizarse.");
        }
    }

    private static void requireLocationInBranch(Location location, UUID branchId) {
        if (!branchId.equals(location.getBranchId())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_BRANCH_MISMATCH",
                    "La ubicación no pertenece a la sucursal indicada.");
        }
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
            if (isLockWaitType(current) || isLockWaitSqlState(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean isLockWaitType(Throwable error) {
        return error instanceof PessimisticLockingFailureException
                || error instanceof PessimisticLockException
                || error instanceof LockTimeoutException;
    }

    private static boolean isLockWaitSqlState(Throwable error) {
        if (!(error instanceof SQLException sql)) {
            return false;
        }
        String state = sql.getSQLState();
        return SQLSTATE_LOCK_NOT_AVAILABLE.equals(state) || SQLSTATE_DEADLOCK_DETECTED.equals(state);
    }

    private Scope requireScope(
            AuthenticatedUser actor, UUID branchId, UUID productId, UUID locationId) {
        UUID tenantId = actor.tenantId();
        Product product = requireBranchAndProduct(actor, branchId, productId);
        Location location = locationRepository.findByTenantIdAndId(tenantId, locationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, LOCATION_NOT_FOUND_CODE, LOCATION_NOT_FOUND_MESSAGE));
        requireLocationInBranch(location, branchId);
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
