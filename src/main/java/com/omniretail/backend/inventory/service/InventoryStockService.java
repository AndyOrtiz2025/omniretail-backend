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

        inventoryBalanceRepository.ensureDefaultLocationBalanceExists(
                command.tenantId(), command.branchId(), command.productId());
        InventoryBalance balance = inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                        command.tenantId(), command.branchId(), command.productId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el balance de inventario."));

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

        inventoryBalanceRepository.ensureLocationBalanceExists(
                command.tenantId(), command.branchId(), command.productId(), location.getId());
        InventoryBalance balance = inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationId(
                        command.tenantId(), command.branchId(), command.productId(), location.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar el balance de inventario de la ubicacion."));

        BigDecimal quantityBefore = balance.getQuantity();
        balance.add(command.qty());

        return saveInboundMovement(command, balance, quantityBefore);
    }

    @Transactional
    public InventoryBalance reserveStock(
            UUID tenantId, UUID branchId, UUID productId, BigDecimal quantity) {
        validateQuantity(quantity);
        InventoryBalance balance = requireDefaultBalance(
                tenantId, branchId, productId, InventoryStockService::insufficientStock);
        try {
            balance.reserve(quantity);
        } catch (IllegalStateException exception) {
            throw insufficientStock();
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
        if (!balance.getBranchId().equals(branchId) || !balance.getProductId().equals(productId)) {
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
        if (!balance.getBranchId().equals(branchId) || !balance.getProductId().equals(productId)) {
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
                .performedByUserId(command.performedByUserId())
                .build());
    }

    private InventoryMovement doDeductStock(DeductStockCommand command) {
        validateQuantity(command.qty());

        InventoryBalance balance = inventoryBalanceRepository
                .findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
                        command.tenantId(), command.branchId(), command.productId())
                .orElseThrow(InventoryStockService::insufficientStock);

        BigDecimal availableQuantity =
                balance.getQuantity().subtract(balance.getReservedQuantity());
        if (command.qty().compareTo(availableQuantity) > 0) {
            throw insufficientStock();
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
