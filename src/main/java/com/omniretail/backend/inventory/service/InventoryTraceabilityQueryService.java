package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.InventoryLotAvailabilityDto;
import com.omniretail.backend.inventory.dto.InventorySerialAvailabilityDto;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import com.omniretail.backend.inventory.repository.InventoryLotBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryLotRepository;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryTraceabilityQueryService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchAccessResolver branchAccessResolver;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryLotRepository lotRepository;
    private final InventoryLotBalanceRepository lotBalanceRepository;
    private final InventorySerialRepository serialRepository;

    public List<InventoryLotAvailabilityDto> availableLots(
            UUID branchId, UUID productId, UUID locationId) {
        LookupScope scope = requireScope(branchId, productId, locationId);
        List<InventoryLotBalance> balances = locationId == null
                ? lotBalanceRepository.findAvailableByTenantBranchAndProduct(
                        scope.tenantId(), branchId, productId)
                : lotBalanceRepository.findAvailableByTenantBranchProductAndLocation(
                        scope.tenantId(), branchId, productId, locationId);
        if (balances.isEmpty()) return List.of();

        Map<UUID, InventoryLot> lots = lotRepository
                .findByTenantIdAndIdIn(
                        scope.tenantId(), balances.stream().map(InventoryLotBalance::getLotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
        return balances.stream()
                .map(balance -> lotAvailability(balance, lots.get(balance.getLotId())))
                .toList();
    }

    public List<InventorySerialAvailabilityDto> availableSerials(
            UUID branchId, UUID productId, UUID locationId, UUID lotId) {
        LookupScope scope = requireScope(branchId, productId, locationId);
        if (lotId != null) requireLot(scope.tenantId(), productId, lotId);

        List<InventorySerial> serials;
        if (locationId == null && lotId == null) {
            serials = serialRepository.findByTenantIdAndBranchIdAndProductIdAndStatusOrderBySerialNumberAsc(
                    scope.tenantId(), branchId, productId, InventorySerialStatus.AVAILABLE);
        } else if (locationId != null && lotId == null) {
            serials = serialRepository
                    .findByTenantIdAndBranchIdAndLocationIdAndProductIdAndStatusOrderBySerialNumberAsc(
                            scope.tenantId(), branchId, locationId, productId, InventorySerialStatus.AVAILABLE);
        } else if (locationId == null) {
            serials = serialRepository
                    .findByTenantIdAndBranchIdAndProductIdAndLotIdAndStatusOrderBySerialNumberAsc(
                            scope.tenantId(), branchId, productId, lotId, InventorySerialStatus.AVAILABLE);
        } else {
            serials = serialRepository
                    .findByTenantIdAndBranchIdAndLocationIdAndProductIdAndLotIdAndStatusOrderBySerialNumberAsc(
                            scope.tenantId(), branchId, locationId, productId, lotId,
                            InventorySerialStatus.AVAILABLE);
        }
        return serials.stream().map(InventoryTraceabilityQueryService::serialAvailability).toList();
    }

    private LookupScope requireScope(UUID branchId, UUID productId, UUID locationId) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        if (!branchAccessResolver.resolve(actor).allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
        productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        if (locationId != null) requireLocation(tenantId, branchId, locationId);
        return new LookupScope(tenantId);
    }

    private void requireLocation(UUID tenantId, UUID branchId, UUID locationId) {
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
                    "La ubicacion debe estar activa.");
        }
    }

    private void requireLot(UUID tenantId, UUID productId, UUID lotId) {
        lotRepository.findByTenantIdAndId(tenantId, lotId)
                .filter(lot -> lot.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", "Lote no encontrado."));
    }

    private static InventoryLotAvailabilityDto lotAvailability(
            InventoryLotBalance balance, InventoryLot lot) {
        if (lot == null) {
            throw new IllegalStateException("El balance de lote no tiene un lote valido.");
        }
        return new InventoryLotAvailabilityDto(
                lot.getId(),
                lot.getLotNumber(),
                lot.getExpirationDate(),
                balance.getQuantity(),
                balance.getReservedQuantity(),
                balance.getQuantity().subtract(balance.getReservedQuantity()),
                balance.getLocationId());
    }

    private static InventorySerialAvailabilityDto serialAvailability(InventorySerial serial) {
        return new InventorySerialAvailabilityDto(
                serial.getId(),
                serial.getSerialNumber(),
                serial.getLotId(),
                serial.getBranchId(),
                serial.getLocationId());
    }

    private record LookupScope(UUID tenantId) {}
}
