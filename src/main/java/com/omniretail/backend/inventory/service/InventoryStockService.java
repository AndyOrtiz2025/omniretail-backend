package com.omniretail.backend.inventory.service;

import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryStockService {

    private static final String GENERIC_DEDUCTION_REASON = "Deducción de inventario";
    private static final String INSUFFICIENT_STOCK_CODE = "INSUFFICIENT_STOCK";
    private static final String INSUFFICIENT_STOCK_MESSAGE = "Stock insuficiente.";

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final LocationRepository locationRepository;
    private final InventoryOperationalLocationService operationalLocations;
    private final EntityManager entityManager;

    @Transactional
    public InventoryMovement deductStock(
            UUID tenantId, UUID branchId, UUID productId, BigDecimal qty) {
        return doDeductStock(new DeductStockCommand(
                tenantId,
                branchId,
                productId,
                qty,
                GENERIC_DEDUCTION_REASON,
                null,
                null,
                null,
                null));
    }

    @Transactional
    public InventoryMovement deductStock(DeductStockCommand command) {
        if (command == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_COMMAND",
                    "Los datos de la deducción son requeridos.");
        }
        return doDeductStock(command);
    }

    @Transactional
    public InventoryMovement incrementStock(AddStockCommand command) {
        if (command == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_COMMAND",
                    "Los datos del incremento son requeridos.");
        }
        validateQuantity(command.qty());

        // Una anulacion de POS restaura la venta original: vuelve al balance del que salio.
        if (command.referenceId() != null
                && command.referenceLineId() != null
                && InventoryOperationalLocationService.VOID_REFERENCE_TYPES.contains(command.referenceType())) {
            return restoreSoldStock(command, command.referenceId(), command.referenceLineId());
        }

        // Con ubicaciones habilitadas la entrada va a la ubicacion asignada; si no, balance NULL heredado.
        InventoryOperationalLocationService.OperationalLocation operational =
                operationalLocations.resolveForInbound(
                        command.tenantId(), command.branchId(), command.productId(), null);
        if (operational.enabled() && operational.locationId() != null) {
            return incrementAtLocation(command, operational.locationId());
        }
        return incrementAtNullBalance(command);
    }

    /**
     * Restaura stock de un producto sin trazabilidad que una venta de POS descontó (anulacion o devolucion):
     * lo devuelve al balance del que salio, identificado con la salida original de la venta
     * ({@code saleId}, {@code saleLineId}) en los movimientos persistidos. No aplica la politica de entradas
     * de ubicacion unica; si el origen no se puede identificar con ubicaciones habilitadas responde 409.
     * Las anulaciones llegan aqui por {@link #incrementStock}; las devoluciones deben invocarlo directamente
     * con la venta y la linea vendida originales.
     */
    @Transactional
    public InventoryMovement restoreSoldStock(AddStockCommand command, UUID saleId, UUID saleLineId) {
        if (command == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_COMMAND",
                    "Los datos del incremento son requeridos.");
        }
        validateQuantity(command.qty());
        InventoryOperationalLocationService.OperationalLocation origin =
                operationalLocations.resolveForRestoration(
                        command.tenantId(),
                        command.branchId(),
                        command.productId(),
                        saleId,
                        saleLineId,
                        command.qty());
        if (origin.locationId() == null) {
            return incrementAtNullBalance(command);
        }
        return incrementAtLocation(command, origin.locationId());
    }

    private InventoryMovement incrementAtNullBalance(AddStockCommand command) {
        inventoryBalanceRepository.ensureDefaultLocationBalanceExists(
                command.tenantId(), command.branchId(), command.productId());
        InventoryBalance balance = inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                        command.tenantId(), command.branchId(), command.productId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el balance de inventario."));
        refreshLockedBalance(balance);

        BigDecimal quantityBefore = balance.getQuantity();
        balance.add(command.qty());

        return saveInboundMovement(command, balance, quantityBefore);
    }

    @Transactional
    public InventoryMovement incrementStockAtLocation(AddStockCommand command, UUID locationId) {
        if (command == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_COMMAND",
                    "Los datos del incremento son requeridos.");
        }
        if (locationId == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_LOCATION",
                    "La ubicacion de inventario es requerida.");
        }
        validateQuantity(command.qty());
        Location location = requireActiveLocation(command.tenantId(), command.branchId(), locationId);
        // Con ubicaciones habilitadas, solo la ubicacion asignada al producto puede recibir stock (409).
        operationalLocations.resolveForInbound(
                command.tenantId(), command.branchId(), command.productId(), location.getId());

        return incrementAtLocation(command, location.getId());
    }

    private InventoryMovement incrementAtLocation(AddStockCommand command, UUID locationId) {
        inventoryBalanceRepository.ensureLocationBalanceExists(
                command.tenantId(), command.branchId(), command.productId(), locationId);
        InventoryBalance balance = inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationId(
                        command.tenantId(), command.branchId(), command.productId(), locationId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el balance de inventario de la ubicacion."));
        refreshLockedBalance(balance);

        BigDecimal quantityBefore = balance.getQuantity();
        balance.add(command.qty());

        return saveInboundMovement(command, balance, quantityBefore);
    }

    @Transactional
    public InventoryBalance reserveStock(
            UUID tenantId, UUID branchId, UUID productId, BigDecimal quantity) {
        validateQuantity(quantity);
        InventoryOperationalLocationService.OperationalLocation operational =
                operationalLocations.resolveForSale(tenantId, branchId, productId);
        InventoryBalance balance = findOperationalBalance(tenantId, branchId, productId, operational)
                .orElseThrow(() -> insufficientStockOrConflict(tenantId, branchId, productId, operational));
        try {
            balance.reserve(quantity);
        } catch (IllegalStateException exception) {
            throw insufficientStockOrConflict(tenantId, branchId, productId, operational);
        }
        return balance;
    }

    @Transactional
    public void consumeReservedStock(
            UUID tenantId, UUID branchId, UUID productId, BigDecimal quantity) {
        validateQuantity(quantity);
        InventoryBalance balance = requireDefaultBalance(
                tenantId, branchId, productId, InventoryStockService::inconsistentReservation);
        try {
            balance.consumeReservation(quantity);
        } catch (IllegalStateException exception) {
            throw inconsistentReservation();
        }
    }

    /** Consume exactamente el balance que fue reservado, conservando la ubicacion de la reserva. */
    @Transactional
    public void consumeReservedStock(
            UUID tenantId, UUID branchId, UUID productId, UUID balanceId, BigDecimal quantity) {
        validateQuantity(quantity);
        InventoryBalance balance = inventoryBalanceRepository.findByTenantIdAndId(tenantId, balanceId)
                .orElseThrow(InventoryStockService::inconsistentReservation);
        consumeReservedStock(tenantId, branchId, productId, balance, quantity);
    }

    void consumeReservedStock(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            InventoryBalance balance,
            BigDecimal quantity) {
        validateQuantity(quantity);
        if (balance == null
                || !balance.getTenantId().equals(tenantId)
                || !balance.getBranchId().equals(branchId)
                || !balance.getProductId().equals(productId)) {
            throw inconsistentReservation();
        }
        try {
            balance.consumeReservation(quantity);
        } catch (IllegalStateException exception) {
            throw inconsistentReservation();
        }
    }

    @Transactional
    public void releaseReservedStock(
            UUID tenantId, UUID branchId, UUID productId, BigDecimal quantity) {
        validateQuantity(quantity);
        InventoryBalance balance = requireDefaultBalance(
                tenantId, branchId, productId, InventoryStockService::inconsistentReservation);
        try {
            balance.releaseReservation(quantity);
        } catch (IllegalStateException exception) {
            throw inconsistentReservation();
        }
    }

    @Transactional
    public void releaseReservedStock(
            UUID tenantId, UUID branchId, UUID productId, UUID balanceId, BigDecimal quantity) {
        validateQuantity(quantity);
        InventoryBalance balance = inventoryBalanceRepository.findByTenantIdAndId(tenantId, balanceId)
                .orElseThrow(InventoryStockService::inconsistentReservation);
        releaseReservedStock(tenantId, branchId, productId, balance, quantity);
    }

    void releaseReservedStock(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            InventoryBalance balance,
            BigDecimal quantity) {
        validateQuantity(quantity);
        if (balance == null
                || !balance.getTenantId().equals(tenantId)
                || !balance.getBranchId().equals(branchId)
                || !balance.getProductId().equals(productId)) {
            throw inconsistentReservation();
        }
        try {
            balance.releaseReservation(quantity);
        } catch (IllegalStateException exception) {
            throw inconsistentReservation();
        }
    }

    private InventoryMovement saveInboundMovement(
            AddStockCommand command, InventoryBalance balance, BigDecimal quantityBefore) {
        return inventoryMovementRepository.save(InventoryMovement.builder()
                .tenantId(command.tenantId())
                .branchId(command.branchId())
                .productId(command.productId())
                .type(InventoryMovementType.in)
                .reason(command.reason())
                .quantity(command.qty())
                .quantityBefore(quantityBefore)
                .quantityAfter(balance.getQuantity())
                .fromLocationId(null)
                .toLocationId(balance.getLocationId())
                .referenceType(command.referenceType())
                .referenceId(command.referenceId())
                .referenceLineId(command.referenceLineId())
                .performedByUserId(command.performedByUserId())
                .build());
    }

    private InventoryMovement doDeductStock(DeductStockCommand command) {
        validateQuantity(command.qty());

        InventoryOperationalLocationService.OperationalLocation operational =
                operationalLocations.resolveForSale(
                        command.tenantId(), command.branchId(), command.productId());
        InventoryBalance balance = findOperationalBalance(
                        command.tenantId(), command.branchId(), command.productId(), operational)
                .orElseThrow(() -> insufficientStockOrConflict(
                        command.tenantId(), command.branchId(), command.productId(), operational));

        BigDecimal availableQuantity =
                balance.getQuantity().subtract(balance.getReservedQuantity());
        if (command.qty().compareTo(availableQuantity) > 0) {
            throw insufficientStockOrConflict(
                    command.tenantId(), command.branchId(), command.productId(), operational);
        }

        BigDecimal quantityBefore = balance.getQuantity();
        balance.deduct(command.qty());

        return inventoryMovementRepository.save(InventoryMovement.builder()
                .tenantId(command.tenantId())
                .branchId(command.branchId())
                .productId(command.productId())
                .type(InventoryMovementType.out)
                .reason(command.reason())
                .quantity(command.qty())
                .quantityBefore(quantityBefore)
                .quantityAfter(balance.getQuantity())
                .fromLocationId(balance.getLocationId())
                .toLocationId(null)
                .referenceType(command.referenceType())
                .referenceId(command.referenceId())
                .referenceLineId(command.referenceLineId())
                .performedByUserId(command.performedByUserId())
                .build());
    }

    private static void validateQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_QUANTITY",
                    "La cantidad debe ser mayor que cero.");
        }
    }

    private Location requireActiveLocation(UUID tenantId, UUID branchId, UUID locationId) {
        Location location = locationRepository
                .findByTenantIdAndId(tenantId, locationId)
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
        return location;
    }

    private static BusinessException insufficientStock() {
        return BusinessException.conflict(INSUFFICIENT_STOCK_CODE, INSUFFICIENT_STOCK_MESSAGE);
    }

    /** "Stock insuficiente", o el conflicto de ubicacion unica si hay saldo en otra ubicacion. */
    private BusinessException insufficientStockOrConflict(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            InventoryOperationalLocationService.OperationalLocation operational) {
        return operationalLocations.insufficientStockFailure(
                tenantId, branchId, productId, operational, InventoryStockService::insufficientStock);
    }

    /** Balance operativo bloqueado: el de la ubicacion asignada, o el NULL heredado. */
    private java.util.Optional<InventoryBalance> findOperationalBalance(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            InventoryOperationalLocationService.OperationalLocation operational) {
        java.util.Optional<InventoryBalance> locked = operational.locationId() == null
                ? inventoryBalanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                        tenantId, branchId, productId)
                : inventoryBalanceRepository.findByTenantIdAndBranchIdAndProductIdAndLocationId(
                        tenantId, branchId, productId, operational.locationId());
        locked.ifPresent(this::refreshLockedBalance);
        return locked;
    }

    /**
     * La politica operativa puede haber cargado esta entidad antes de que el query pesimista obtuviera
     * el lock. En este punto aun no hay cambios pendientes sobre el balance, por lo que refrescar la misma
     * fila bloqueada conserva quantity y reservedQuantity confirmados por la transaccion anterior.
     */
    private void refreshLockedBalance(InventoryBalance balance) {
        entityManager.refresh(balance);
    }

    private InventoryBalance requireDefaultBalance(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            java.util.function.Supplier<? extends RuntimeException> missingBalanceException) {
        return inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                        tenantId, branchId, productId)
                .orElseThrow(missingBalanceException);
    }

    private static BusinessException inconsistentReservation() {
        return BusinessException.conflict(
                "INVENTORY_RESERVATION_INCONSISTENT",
                "La reserva no coincide con el balance de inventario.");
    }
}
