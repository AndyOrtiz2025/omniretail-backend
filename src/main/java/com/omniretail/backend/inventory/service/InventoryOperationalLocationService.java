package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.BusinessCapabilitiesConfig;
import com.omniretail.backend.administration.repository.BusinessCapabilitiesConfigRepository;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.entity.ProductInventorySettings;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryLotBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ubicacion operativa unica por producto y sucursal.
 *
 * <p>Con el control de ubicaciones habilitado ({@code supportsMultipleLocations}), el unico balance
 * operativo de un producto en una sucursal es el de {@code product_inventory_settings.default_location_id}.
 * Reservas, salidas y entradas nuevas se resuelven contra ese balance:
 *
 * <ul>
 *   <li>Entradas nuevas exigen una ubicacion asignada y activa, y rechazan (409) cualquier otra ubicacion
 *       solicitada. Un producto heredado sin asignacion que ya opera sobre su balance sin ubicacion (NULL)
 *       sigue recibiendo en ese balance; no se mueve nada.
 *   <li>Reservas y salidas usan el balance de la ubicacion asignada; sin asignacion conservan el balance
 *       sin ubicacion (NULL) por compatibilidad.
 *   <li>Las restauraciones de ventas (anulaciones y devoluciones) vuelven al balance de origen de la salida
 *       original, resuelto con los movimientos persistidos; nunca a la asignada ni a NULL por suposicion.
 *   <li>Con el control deshabilitado (o sin configuracion del negocio) todo sigue como antes: balance NULL
 *       y la ubicacion que indique el llamador.
 * </ul>
 *
 * <p>Nunca mueve, fusiona ni borra balances, lotes ni series heredados. Si un producto conserva saldo en
 * otra ubicacion, las operaciones que no pueden cumplir la politica fallan con un error claro y el hecho se
 * registra en el log.
 *
 * <p>Concurrencia: toda operacion que decide o cambia la ubicacion toma primero un bloqueo de la fila del
 * producto y luego el del balance, siempre en ese orden. Las decisiones (reservas, salidas, entradas,
 * restauraciones) toman el bloqueo compartido ({@code FOR SHARE}); el cambio de ubicacion asignada toma el
 * exclusivo ({@code FOR UPDATE}). Asi un cambio de configuracion y una reserva o recepcion no pueden
 * cruzarse, pero las decisiones entre si no se serializan por producto (ni entre sucursales) y el bloqueo
 * compartido no choca con las claves foraneas de los movimientos que inserten otros flujos mientras tienen
 * un balance bloqueado. El bloqueo del producto solo se toma con ubicaciones habilitadas. Antes de ambos, las
 * decisiones toman el bloqueo compartido de la fila de configuracion del tenant y el cambio de configuracion
 * que apaga las ubicaciones ({@link #assertCanDisableLocations}) el exclusivo: configuracion -> producto ->
 * balance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryOperationalLocationService {

    public static final String LOCATION_NOT_ASSIGNED_CODE = "INVENTORY_LOCATION_NOT_ASSIGNED";
    public static final String LOCATION_MISMATCH_CODE = "INVENTORY_LOCATION_MISMATCH";
    public static final String LOCATION_CONFLICT_CODE = "INVENTORY_LOCATION_CONFLICT";
    public static final String ASSIGNED_LOCATION_INVALID_CODE = "INVENTORY_ASSIGNED_LOCATION_INVALID";
    public static final String ASSIGNED_LOCATION_INACTIVE_CODE = "INVENTORY_ASSIGNED_LOCATION_INACTIVE";
    public static final String LOCATION_CHANGE_BLOCKED_CODE = "INVENTORY_LOCATION_CHANGE_BLOCKED";
    public static final String LOCATION_REQUIRED_CODE = "INVENTORY_LOCATION_REQUIRED";
    public static final String LOCATIONS_IN_USE_CODE = "INVENTORY_LOCATIONS_IN_USE";
    public static final String TRANSFER_DESTINATION_INVALID_CODE = "INVENTORY_TRANSFER_DESTINATION_INVALID";
    public static final String RESTORE_ORIGIN_NOT_FOUND_CODE = "INVENTORY_RESTORE_ORIGIN_NOT_FOUND";
    public static final String RESTORE_ORIGIN_AMBIGUOUS_CODE = "INVENTORY_RESTORE_ORIGIN_AMBIGUOUS";
    public static final String RESTORE_ORIGIN_INVALID_CODE = "INVENTORY_RESTORE_ORIGIN_INVALID";

    /** Referencias de las salidas de POS que una anulacion o devolucion restaura. */
    public static final Set<String> SALE_REFERENCE_TYPES = Set.of("POS_SALE", "POS_KIT_SALE");

    /** Unica referencia de salida que existe sin linea (ventas anteriores a la migracion 045). */
    private static final String LEGACY_SALE_REFERENCE_TYPE = "POS_SALE";

    /** Referencias de las anulaciones de POS (misma venta y misma linea que la salida original). */
    public static final Set<String> VOID_REFERENCE_TYPES = Set.of("POS_SALE_VOID", "POS_KIT_SALE_VOID");

    private final BusinessCapabilitiesConfigRepository capabilitiesRepository;
    private final ProductInventorySettingsRepository settingsRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryLotBalanceRepository lotBalanceRepository;
    private final InventorySerialRepository serialRepository;
    private final InventoryMovementRepository movementRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final EntityManager entityManager;

    /**
     * Resultado de la resolucion. {@code enabled=false} significa comportamiento heredado; {@code locationId}
     * null significa balance sin ubicacion.
     */
    public record OperationalLocation(boolean enabled, UUID locationId) {}

    /**
     * Una linea de entrada a validar: el producto y la ubicacion prevista (null = la asignada, salvo que el
     * modo la exija). {@code lineRef} es una referencia opaca del llamador (p. ej. el id de la linea de la
     * recepcion) que se devuelve en los problemas para ubicar la linea; puede ser null.
     */
    public record InboundTarget(UUID lineRef, UUID productId, UUID locationId) {
        public InboundTarget(UUID productId, UUID locationId) {
            this(null, productId, locationId);
        }
    }

    /**
     * Como exige la operacion la ubicacion de la entrada.
     *
     * <ul>
     *   <li>{@code OPTIONAL}: el llamador puede no indicarla y se usa la asignada, o el balance sin ubicacion de
     *       un producto heredado (igual que {@link #resolveForInbound}).
     *   <li>{@code REQUIRED}: la operacion registra la entrada en una ubicacion explicita de cada linea
     *       (p. ej. recepciones de compra); una linea sin ubicacion es un problema.
     *   <li>{@code ASSIGNED_REQUIRED}: la ubicacion se elegira despues y debe ser una ubicacion asignada
     *       (p. ej. el destino de un traslado antes de despacharlo); un producto heredado que solo opera sobre
     *       el balance sin ubicacion no puede recibir ahi y se reporta como sin asignacion.
     * </ul>
     */
    public enum InboundMode {
        OPTIONAL,
        REQUIRED,
        ASSIGNED_REQUIRED
    }

    /** Motivo por el que una entrada no podria registrarse hoy (ver {@link #inboundIssues}). */
    public record InboundIssue(UUID productId, String code, String message, UUID lineRef) {
        public InboundIssue(UUID productId, String code, String message) {
            this(productId, code, message, null);
        }
    }

    /**
     * Guarda de la transicion habilitado -> deshabilitado de {@code supportsMultipleLocations}. Toma el bloqueo
     * exclusivo de la fila de configuracion del tenant (las decisiones de inventario toman el compartido, ver
     * {@link #locationsEnabledForDecision}), de modo que ninguna reserva, venta o entrada en curso puede
     * cruzarse con el cambio, y rechaza con 409 si algun balance con ubicacion (asignada o heredada no asignada)
     * conserva existencias o reservas: con el control apagado las ventas solo ven el balance sin ubicacion y
     * ese inventario quedaria oculto. No mueve, borra ni fusiona nada. Si la capacidad ya estaba apagada, o el
     * tenant no tiene configuracion, no hace nada. Debe llamarse dentro de la transaccion que guarda la
     * configuracion, antes de aplicarla.
     */
    @Transactional
    public void assertCanDisableLocations(UUID tenantId) {
        List<?> rows = entityManager
                .createNativeQuery("SELECT supports_multiple_locations FROM business_capabilities_configs "
                        + "WHERE tenant_id = :tenantId FOR UPDATE")
                .setParameter("tenantId", tenantId)
                .getResultList();
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0))) {
            return;
        }
        Number atRisk = (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM (SELECT DISTINCT branch_id, product_id "
                        + "FROM inventory_balances WHERE tenant_id = :tenantId AND location_id IS NOT NULL "
                        + "AND (quantity > 0 OR reserved_quantity > 0)) at_risk")
                .setParameter("tenantId", tenantId)
                .getSingleResult();
        if (atRisk.longValue() > 0) {
            log.warn(
                    "Desactivacion de ubicaciones rechazada: tenant={} conserva {} producto(s) con existencias "
                            + "o reservas en ubicaciones",
                    tenantId, atRisk);
            throw BusinessException.conflict(
                    LOCATIONS_IN_USE_CODE,
                    "No se puede desactivar el control de ubicaciones: " + atRisk.longValue()
                            + " producto(s) conservan existencias o reservas en ubicaciones y quedarían "
                            + "ocultos para las ventas. Regularice ese inventario antes de desactivarlo.");
        }
    }

    /**
     * Lectura de la capacidad para las decisiones de inventario (reservas, salidas, entradas, restauraciones y
     * cambios de asignacion): toma el bloqueo compartido de la fila de configuracion hasta el fin de la
     * transaccion. Las decisiones no se bloquean entre si; solo esperan a un cambio de configuracion en curso
     * y este espera a que terminen. Orden de bloqueos: configuracion -> producto -> balance.
     */
    private boolean locationsEnabledForDecision(UUID tenantId) {
        List<?> rows = entityManager
                .createNativeQuery("SELECT supports_multiple_locations FROM business_capabilities_configs "
                        + "WHERE tenant_id = :tenantId FOR SHARE")
                .setParameter("tenantId", tenantId)
                .getResultList();
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    /** Sin configuracion del negocio se considera deshabilitado (comportamiento heredado). */
    @Transactional(readOnly = true)
    public boolean locationsEnabled(UUID tenantId) {
        return capabilitiesRepository
                .findByTenantId(tenantId)
                .map(BusinessCapabilitiesConfig::isSupportsMultipleLocations)
                .orElse(false);
    }

    /**
     * Entradas nuevas (incrementos, recepciones, ajustes de entrada, traslados recibidos).
     *
     * @param requestedLocationId ubicacion pedida por el llamador; null = usar la asignada.
     */
    @Transactional
    public OperationalLocation resolveForInbound(
            UUID tenantId, UUID branchId, UUID productId, UUID requestedLocationId) {
        if (!locationsEnabledForDecision(tenantId)) {
            return new OperationalLocation(false, requestedLocationId);
        }
        lockProductShared(tenantId, productId);
        InboundEvaluation evaluation = evaluateInbound(
                tenantId, branchId, productId, requestedLocationId, InboundMode.OPTIONAL);
        if (evaluation.failure() != null) {
            throw evaluation.failure();
        }
        return new OperationalLocation(true, evaluation.locationId());
    }

    /**
     * Version compatible por producto ({@code producto -> ubicacion prevista, null = la asignada}), modo
     * {@link InboundMode#OPTIONAL}. Un mapa no puede representar dos lineas del mismo producto; para
     * recepciones usar {@link #inboundIssues(UUID, UUID, Collection, InboundMode)}.
     */
    @Transactional(readOnly = true)
    public List<InboundIssue> inboundIssues(
            UUID tenantId, UUID branchId, Map<UUID, UUID> requestedLocationByProduct) {
        if (requestedLocationByProduct == null || requestedLocationByProduct.isEmpty()) {
            return List.of();
        }
        List<InboundTarget> targets = new ArrayList<>(requestedLocationByProduct.size());
        new LinkedHashMap<>(requestedLocationByProduct).forEach(
                (productId, locationId) -> targets.add(new InboundTarget(productId, locationId)));
        return inboundIssues(tenantId, branchId, targets, InboundMode.OPTIONAL);
    }

    /**
     * Prevencion para flujos que confirman mas tarde (recepciones de compra, traslados): informa, sin
     * bloquear ni modificar nada, que lineas no podrian recibir stock hoy en la sucursal y por que. Cada
     * linea se evalua por separado (se conservan las repetidas del mismo producto) y los problemas llevan su
     * {@code lineRef}. Aplica las mismas reglas que {@link #resolveForInbound} mas la existencia del producto
     * en el tenant y las exigencias del {@code mode}; el resultado es una foto, la decision firme sigue
     * tomandose en la entrada. Con el control deshabilitado nunca informa problemas.
     */
    @Transactional(readOnly = true)
    public List<InboundIssue> inboundIssues(
            UUID tenantId, UUID branchId, Collection<InboundTarget> targets, InboundMode mode) {
        if (targets == null || targets.isEmpty() || !locationsEnabled(tenantId)) {
            return List.of();
        }
        InboundMode effectiveMode = mode == null ? InboundMode.OPTIONAL : mode;
        Map<InboundTarget, BusinessException> evaluated = new HashMap<>();
        List<InboundIssue> issues = new ArrayList<>();
        for (InboundTarget target : targets) {
            if (target == null) {
                continue;
            }
            // El resultado depende del producto y la ubicacion, no de la linea: se evalua una sola vez.
            InboundTarget key = new InboundTarget(null, target.productId(), target.locationId());
            BusinessException failure;
            if (evaluated.containsKey(key)) {
                failure = evaluated.get(key);
            } else {
                failure = inboundFailure(tenantId, branchId, key, effectiveMode);
                evaluated.put(key, failure);
            }
            if (failure != null) {
                issues.add(new InboundIssue(
                        target.productId(), failure.getCode(), failure.getMessage(), target.lineRef()));
            }
        }
        return issues;
    }

    /**
     * Prevencion de traslados: comprueba, sin escribir ni bloquear, que cada producto pueda recibirse en la
     * sucursal destino con la ubicacion que se elegira al recibir (modo {@link InboundMode#ASSIGNED_REQUIRED}:
     * debe tener una ubicacion asignada, activa y de esa sucursal, sin existencias en otra). Responde 409
     * {@value #TRANSFER_DESTINATION_INVALID_CODE} identificando los productos; no hace nada con ubicaciones
     * deshabilitadas. Se llama al aprobar y de nuevo al confirmar el despacho, antes de mover mercancia.
     */
    @Transactional(readOnly = true)
    public void requireTransferDestinationReceivable(
            UUID tenantId, UUID destinationBranchId, Collection<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        List<InboundIssue> issues = inboundIssues(
                tenantId,
                destinationBranchId,
                productIds.stream().map(productId -> new InboundTarget(productId, null)).toList(),
                InboundMode.ASSIGNED_REQUIRED);
        if (issues.isEmpty()) {
            return;
        }
        String detail = issues.stream()
                .map(issue -> productRepository
                                .findByTenantIdAndId(tenantId, issue.productId())
                                .map(Product::getSku)
                                .orElse(String.valueOf(issue.productId()))
                        + ": " + issue.message())
                .collect(Collectors.joining(" | "));
        throw BusinessException.conflict(
                TRANSFER_DESTINATION_INVALID_CODE,
                "La sucursal destino no puede recibir el traslado tal como está configurada: " + detail);
    }

    private BusinessException inboundFailure(
            UUID tenantId, UUID branchId, InboundTarget target, InboundMode mode) {
        if (target.productId() == null
                || !productRepository.existsByTenantIdAndId(tenantId, target.productId())) {
            return productNotFound();
        }
        return evaluateInbound(tenantId, branchId, target.productId(), target.locationId(), mode)
                .failure();
    }

    /**
     * Restauracion de una venta (anulacion o devolucion) de un producto sin trazabilidad: el stock vuelve al
     * balance del que salio, identificado con los movimientos de salida persistidos de la misma venta y la
     * misma linea (o, en ventas anteriores a la migracion 045, las salidas {@code POS_SALE} de la venta sin
     * linea para ese producto). No se aplica la politica de entradas (ubicacion asignada, inactiva, saldo en otra
     * ubicacion): la unidad regresa a donde estaba, sin elegir la asignada ni NULL por suposicion.
     *
     * <p>Con ubicaciones habilitadas, un origen no identificable, ambiguo o invalido responde 409 sin tocar
     * nada. Con el control deshabilitado cualquiera de esos casos conserva el comportamiento heredado
     * (balance NULL). El resultado lleva {@code locationId} null para el balance NULL de origen.
     */
    @Transactional
    public OperationalLocation resolveForRestoration(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID saleId,
            UUID saleLineId,
            BigDecimal quantity) {
        boolean enabled = locationsEnabledForDecision(tenantId);
        if (enabled) {
            lockProductShared(tenantId, productId);
        }
        RestoreOrigin origin = restoreOrigin(tenantId, branchId, productId, saleId, saleLineId, quantity);
        if (origin.failureCode() != null) {
            if (!enabled) {
                return new OperationalLocation(false, null);
            }
            log.warn(
                    "Restauracion sin origen verificable: tenant={} sucursal={} producto={} venta={} "
                            + "linea={} codigo={}",
                    tenantId, branchId, productId, saleId, saleLineId, origin.failureCode());
            throw BusinessException.conflict(origin.failureCode(), origin.failureMessage());
        }
        if (enabled) {
            UUID assigned = assignedLocationId(tenantId, branchId, productId);
            if (!Objects.equals(assigned, origin.locationId())) {
                log.warn(
                        "Restauracion al balance de origen {} distinto de la ubicacion asignada {}: "
                                + "tenant={} sucursal={} producto={}",
                        origin.locationId(), assigned, tenantId, branchId, productId);
            }
        }
        return new OperationalLocation(enabled, origin.locationId());
    }

    /**
     * Reservas y salidas. Sin asignacion (o con el control deshabilitado) se conserva el balance NULL
     * heredado. Una ubicacion asignada inactiva no aporta existencias vendibles.
     */
    @Transactional
    public OperationalLocation resolveForSale(UUID tenantId, UUID branchId, UUID productId) {
        if (!locationsEnabledForDecision(tenantId)) {
            return new OperationalLocation(false, null);
        }
        lockProductShared(tenantId, productId);
        UUID assigned = assignedLocationId(tenantId, branchId, productId);
        if (assigned == null) {
            return new OperationalLocation(true, null);
        }
        requireUsableAssignedLocation(tenantId, branchId, assigned);
        return new OperationalLocation(true, assigned);
    }

    /**
     * Error a lanzar cuando el balance operativo no alcanza. Si el producto conserva saldo en otra
     * ubicacion se informa el conflicto de ubicacion unica en vez de un "stock insuficiente" ambiguo.
     */
    @Transactional(readOnly = true)
    public BusinessException insufficientStockFailure(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            OperationalLocation operational,
            Supplier<BusinessException> fallback) {
        if (operational.enabled()
                && !stockedOutsideLocation(tenantId, branchId, productId, operational.locationId())
                        .isEmpty()) {
            log.warn(
                    "Venta rechazada por inventario con ubicacion unica incumplida: tenant={} sucursal={} "
                            + "producto={} operativa={} conserva saldo en otra ubicacion",
                    tenantId, branchId, productId, operational.locationId());
            return conflictingBalances();
        }
        return fallback.get();
    }

    /**
     * Valida que {@code newLocationId} pueda pasar a ser la ubicacion asignada. Se permite asignar la
     * ubicacion donde ya esta todo el saldo; se bloquea si queda saldo, reservas, lotes o series en otra.
     */
    @Transactional
    public void assertAssignmentChangeAllowed(
            UUID tenantId, UUID branchId, UUID productId, UUID newLocationId) {
        if (!locationsEnabledForDecision(tenantId)) {
            return;
        }
        lockProductExclusive(tenantId, productId);
        UUID current = assignedLocationId(tenantId, branchId, productId);
        if (Objects.equals(current, newLocationId)) {
            return;
        }
        if (!stockedOutsideLocation(tenantId, branchId, productId, newLocationId).isEmpty()
                || lotBalanceRepository.existsStockedOutsideLocation(
                        tenantId, branchId, productId, newLocationId)
                || serialRepository.existsInStockOutsideLocation(
                        tenantId, branchId, productId, newLocationId)) {
            throw BusinessException.conflict(
                    LOCATION_CHANGE_BLOCKED_CODE,
                    "No se puede cambiar la ubicación asignada mientras el producto tenga existencias, "
                            + "reservas, lotes o series en otra ubicación de la sucursal.");
        }
    }

    /**
     * Cantidad vendible (cantidad - reservado, nunca negativa) del balance operativo. Mismas reglas de
     * elegibilidad que las reservas; sin bloqueo. Pensada para que el catalogo publico no duplique reglas.
     */
    @Transactional(readOnly = true)
    public BigDecimal availableQuantity(UUID tenantId, UUID branchId, UUID productId) {
        UUID target = null;
        if (locationsEnabled(tenantId)) {
            UUID assigned = assignedLocationId(tenantId, branchId, productId);
            if (assigned != null) {
                if (!isUsableLocation(tenantId, branchId, assigned)) {
                    return BigDecimal.ZERO;
                }
                target = assigned;
            }
        }
        UUID operational = target;
        return balanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId)
                .stream()
                .filter(balance -> Objects.equals(balance.getLocationId(), operational))
                .findFirst()
                .map(InventoryOperationalLocationService::available)
                .orElse(BigDecimal.ZERO);
    }

    /** Version por lote de {@link #availableQuantity}: productos sin balance operativo no aparecen. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> availableByProduct(UUID tenantId, UUID branchId) {
        boolean enabled = locationsEnabled(tenantId);
        Map<UUID, UUID> assignedByProduct = new HashMap<>();
        Set<UUID> usableLocations = Set.of();
        if (enabled) {
            for (ProductInventorySettings settings :
                    settingsRepository.findByTenantIdAndBranchId(tenantId, branchId)) {
                if (settings.getDefaultLocationId() != null) {
                    assignedByProduct.put(settings.getProductId(), settings.getDefaultLocationId());
                }
            }
            if (!assignedByProduct.isEmpty()) {
                usableLocations = locationRepository
                        .findByTenantIdAndIdIn(tenantId, Set.copyOf(assignedByProduct.values()))
                        .stream()
                        .filter(location -> branchId.equals(location.getBranchId()))
                        .filter(location -> location.getStatus() == LocationStatus.active)
                        .map(Location::getId)
                        .collect(Collectors.toSet());
            }
        }
        Map<UUID, BigDecimal> availableByProduct = new HashMap<>();
        for (InventoryBalance balance : balanceRepository.findByTenantIdAndBranchId(tenantId, branchId)) {
            UUID target = assignedByProduct.get(balance.getProductId());
            if (target != null && !usableLocations.contains(target)) {
                continue;
            }
            if (!Objects.equals(balance.getLocationId(), target)) {
                continue;
            }
            availableByProduct.merge(balance.getProductId(), available(balance), BigDecimal::add);
        }
        return availableByProduct;
    }

    /**
     * Bloqueo exclusivo de la fila del producto (el mismo que usan los servicios de catalogo). Solo lo toma el
     * cambio de ubicacion asignada: espera a que terminen las decisiones en curso y las detiene mientras dura.
     */
    private void lockProductExclusive(UUID tenantId, UUID productId) {
        productRepository
                .findForUpdateByTenantIdAndId(tenantId, productId)
                .orElseThrow(InventoryOperationalLocationService::productNotFound);
    }

    /**
     * Bloqueo compartido de la fila del producto (FOR SHARE): conflicta con el cambio de ubicacion asignada y
     * con las ediciones de catalogo, pero no con otras decisiones ni con el KEY SHARE que toma una clave
     * foranea al insertar movimientos. Tambien valida que el producto exista para el tenant.
     */
    private void lockProductShared(UUID tenantId, UUID productId) {
        List<?> rows = entityManager
                .createNativeQuery(
                        "SELECT id FROM products WHERE tenant_id = :tenantId AND id = :productId FOR SHARE")
                .setParameter("tenantId", tenantId)
                .setParameter("productId", productId)
                .getResultList();
        if (rows.isEmpty()) {
            throw productNotFound();
        }
    }

    /** Resultado de evaluar una entrada: la ubicacion destino (null = balance NULL) o el motivo del rechazo. */
    private record InboundEvaluation(UUID locationId, BusinessException failure) {}

    private InboundEvaluation evaluateInbound(
            UUID tenantId, UUID branchId, UUID productId, UUID requestedLocationId, InboundMode mode) {
        if (mode == InboundMode.REQUIRED && requestedLocationId == null) {
            return new InboundEvaluation(null, BusinessException.conflict(
                    LOCATION_REQUIRED_CODE,
                    "La entrada debe indicar la ubicación donde se registra el producto."));
        }
        UUID assigned = assignedLocationId(tenantId, branchId, productId);
        if (assigned == null) {
            if (!hasLegacyNullBalance(tenantId, branchId, productId)) {
                return new InboundEvaluation(null, BusinessException.conflict(
                        LOCATION_NOT_ASSIGNED_CODE,
                        "El producto no tiene una ubicación asignada en esta sucursal. "
                                + "Asigne una ubicación antes de registrar entradas."));
            }
            // Producto heredado que ya opera sobre su balance sin ubicacion: sigue recibiendo ahi.
            if (requestedLocationId != null) {
                return new InboundEvaluation(null, BusinessException.conflict(
                        LOCATION_MISMATCH_CODE,
                        "El producto opera sobre el balance sin ubicación de la sucursal. Asigne la "
                                + "ubicación al producto antes de recibir en una ubicación específica."));
            }
            if (mode == InboundMode.ASSIGNED_REQUIRED) {
                return new InboundEvaluation(null, BusinessException.conflict(
                        LOCATION_NOT_ASSIGNED_CODE,
                        "El producto opera sobre el balance sin ubicación de la sucursal y no puede recibir "
                                + "en una ubicación. Asigne una ubicación antes de recibirlo."));
            }
            if (!stockedOutsideLocation(tenantId, branchId, productId, null).isEmpty()) {
                return new InboundEvaluation(null, conflictingBalancesLogged(tenantId, branchId, productId, null));
            }
            return new InboundEvaluation(null, null);
        }
        BusinessException unusable = unusableAssignedLocation(tenantId, branchId, assigned);
        if (unusable != null) {
            return new InboundEvaluation(null, unusable);
        }
        if (requestedLocationId != null && !requestedLocationId.equals(assigned)) {
            return new InboundEvaluation(null, BusinessException.conflict(
                    LOCATION_MISMATCH_CODE,
                    "La ubicación solicitada no es la ubicación asignada al producto en esta sucursal."));
        }
        if (!stockedOutsideLocation(tenantId, branchId, productId, assigned).isEmpty()) {
            return new InboundEvaluation(null, conflictingBalancesLogged(tenantId, branchId, productId, assigned));
        }
        return new InboundEvaluation(assigned, null);
    }

    private boolean hasLegacyNullBalance(UUID tenantId, UUID branchId, UUID productId) {
        return balanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId)
                .stream()
                .anyMatch(balance -> balance.getLocationId() == null);
    }

    private BusinessException conflictingBalancesLogged(
            UUID tenantId, UUID branchId, UUID productId, UUID operationalLocationId) {
        log.warn(
                "Inventario con ubicacion unica incumplida: tenant={} sucursal={} producto={} "
                        + "conserva saldo fuera del balance operativo {}",
                tenantId, branchId, productId, operationalLocationId);
        return conflictingBalances();
    }

    /** Origen del stock vendido, o el motivo por el que no se pudo identificar. */
    private record RestoreOrigin(UUID locationId, String failureCode, String failureMessage) {
        static RestoreOrigin of(UUID locationId) {
            return new RestoreOrigin(locationId, null, null);
        }

        static RestoreOrigin failed(String code, String message) {
            return new RestoreOrigin(null, code, message);
        }
    }

    private RestoreOrigin restoreOrigin(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID saleId,
            UUID saleLineId,
            BigDecimal quantity) {
        if (saleId == null || saleLineId == null) {
            return RestoreOrigin.failed(
                    RESTORE_ORIGIN_NOT_FOUND_CODE,
                    "No se puede identificar la venta original del inventario a restaurar.");
        }
        List<InventoryMovement> sold = soldMovements(tenantId, branchId, productId, saleId, saleLineId);
        if (sold.isEmpty()) {
            return RestoreOrigin.failed(
                    RESTORE_ORIGIN_NOT_FOUND_CODE,
                    "No existe la salida original de inventario de esta venta para el producto.");
        }
        Set<UUID> locations = sold.stream()
                .map(InventoryMovement::getFromLocationId)
                .collect(Collectors.toSet());
        if (locations.size() != 1) {
            return RestoreOrigin.failed(
                    RESTORE_ORIGIN_AMBIGUOUS_CODE,
                    "La venta descontó el producto de más de un balance; no se puede elegir el de origen.");
        }
        BigDecimal soldQuantity = sold.stream()
                .map(InventoryMovement::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (quantity != null && quantity.compareTo(soldQuantity) > 0) {
            return RestoreOrigin.failed(
                    RESTORE_ORIGIN_INVALID_CODE,
                    "La cantidad a restaurar supera lo descontado por la venta original.");
        }
        UUID origin = locations.iterator().next();
        if (origin != null && !isExistingBranchLocation(tenantId, branchId, origin)) {
            return RestoreOrigin.failed(
                    RESTORE_ORIGIN_INVALID_CODE,
                    "La ubicación de origen de la venta ya no existe en la sucursal.");
        }
        return RestoreOrigin.of(origin);
    }

    /**
     * Salidas de la venta para el producto. Primero por linea (ventas con {@code reference_line_id}); si no hay,
     * y solo entonces, las salidas historicas {@code POS_SALE} de la misma venta sin linea (anteriores a la
     * migracion 045), que son las unicas que POS tambien restaura por esa via. Siempre del mismo tenant,
     * sucursal y producto. Una venta moderna nunca llega al respaldo: sus movimientos llevan linea.
     */
    private List<InventoryMovement> soldMovements(
            UUID tenantId, UUID branchId, UUID productId, UUID saleId, UUID saleLineId) {
        List<InventoryMovement> byLine = movementRepository
                .findByTenantIdAndReferenceTypeInAndReferenceLineIdInOrderByCreatedAtAscIdAsc(
                        tenantId, SALE_REFERENCE_TYPES, List.of(saleLineId))
                .stream()
                .filter(movement -> isSaleOutOf(movement, saleId, branchId, productId))
                .toList();
        if (!byLine.isEmpty()) {
            return byLine;
        }
        return movementRepository
                .findByTenantIdAndReferenceTypeInAndReferenceIdOrderByCreatedAtAscIdAsc(
                        tenantId, Set.of(LEGACY_SALE_REFERENCE_TYPE), saleId)
                .stream()
                .filter(movement -> movement.getReferenceLineId() == null)
                .filter(movement -> isSaleOutOf(movement, saleId, branchId, productId))
                .toList();
    }

    private static boolean isSaleOutOf(
            InventoryMovement movement, UUID saleId, UUID branchId, UUID productId) {
        return movement.getType() == InventoryMovementType.out
                && saleId.equals(movement.getReferenceId())
                && branchId.equals(movement.getBranchId())
                && productId.equals(movement.getProductId());
    }

    /** Existe y es de la sucursal; el estado no importa porque la unidad vuelve a donde estaba. */
    private boolean isExistingBranchLocation(UUID tenantId, UUID branchId, UUID locationId) {
        return locationRepository
                .findByTenantIdAndId(tenantId, locationId)
                .filter(found -> branchId.equals(found.getBranchId()))
                .isPresent();
    }

    private UUID assignedLocationId(UUID tenantId, UUID branchId, UUID productId) {
        return settingsRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId)
                .map(ProductInventorySettings::getDefaultLocationId)
                .orElse(null);
    }

    private void requireUsableAssignedLocation(UUID tenantId, UUID branchId, UUID locationId) {
        BusinessException unusable = unusableAssignedLocation(tenantId, branchId, locationId);
        if (unusable != null) {
            throw unusable;
        }
    }

    /** null si la ubicacion asignada existe, es de la sucursal y esta activa; si no, el motivo (409). */
    private BusinessException unusableAssignedLocation(UUID tenantId, UUID branchId, UUID locationId) {
        Location location = locationRepository
                .findByTenantIdAndId(tenantId, locationId)
                .filter(found -> branchId.equals(found.getBranchId()))
                .orElse(null);
        if (location == null) {
            return BusinessException.conflict(
                    ASSIGNED_LOCATION_INVALID_CODE,
                    "La ubicación asignada al producto no existe o no pertenece a la sucursal.");
        }
        if (location.getStatus() != LocationStatus.active) {
            return BusinessException.conflict(
                    ASSIGNED_LOCATION_INACTIVE_CODE,
                    "La ubicación asignada al producto está inactiva. Asigne una ubicación activa.");
        }
        return null;
    }

    private static BusinessException productNotFound() {
        return new BusinessException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "PRODUCT_NOT_FOUND",
                "Producto no encontrado.");
    }

    private boolean isUsableLocation(UUID tenantId, UUID branchId, UUID locationId) {
        return locationRepository
                .findByTenantIdAndId(tenantId, locationId)
                .filter(found -> branchId.equals(found.getBranchId()))
                .filter(found -> found.getStatus() == LocationStatus.active)
                .isPresent();
    }

    /** Balances con saldo o reserva fuera de la ubicacion indicada (null = balance sin ubicacion). */
    private List<InventoryBalance> stockedOutsideLocation(
            UUID tenantId, UUID branchId, UUID productId, UUID locationId) {
        return balanceRepository
                .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, productId)
                .stream()
                .filter(balance -> balance.getQuantity().signum() > 0
                        || balance.getReservedQuantity().signum() > 0)
                .filter(balance -> !Objects.equals(balance.getLocationId(), locationId))
                .toList();
    }

    private static BigDecimal available(InventoryBalance balance) {
        return balance.getQuantity().subtract(balance.getReservedQuantity()).max(BigDecimal.ZERO);
    }

    private static BusinessException conflictingBalances() {
        return BusinessException.conflict(
                LOCATION_CONFLICT_CODE,
                "El producto conserva existencias en otra ubicación de la sucursal y no cumple la política "
                        + "de ubicación única. Regularice el inventario antes de continuar.");
    }
}
