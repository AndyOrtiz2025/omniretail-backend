package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.ExpiringLotDto;
import com.omniretail.backend.inventory.dto.InventoryLotAvailabilityDto;
import com.omniretail.backend.inventory.dto.InventorySerialAvailabilityDto;
import com.omniretail.backend.inventory.entity.InventoryLot;
import com.omniretail.backend.inventory.entity.InventoryLotBalance;
import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import com.omniretail.backend.inventory.repository.InventoryLotBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryLotBalanceRepository.ExpiringLotProjection;
import com.omniretail.backend.inventory.repository.InventoryLotRepository;
import com.omniretail.backend.inventory.repository.InventorySerialRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private final TenantBusinessDateService businessDateService;

    public List<InventoryLotAvailabilityDto> availableLots(
            UUID branchId, UUID productId, UUID locationId) {
        LookupScope scope = requireScope(branchId, productId, locationId, true);
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
        LookupScope scope = requireScope(branchId, productId, locationId, true);
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

    public PageResponse<ExpiringLotDto> expiringLots(
            UUID branchId,
            int days,
            UUID productId,
            UUID locationId,
            Pageable requestedPageable) {
        if (days < 1 || days > 365) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "EXPIRATION_DAYS_INVALID",
                    "El rango de expiracion debe estar entre 1 y 365 dias.");
        }
        LookupScope scope = requireScope(branchId, productId, locationId, false);
        LocalDate businessDate = businessDateService.currentDate(scope.tenantId());
        Pageable pageable = PageRequest.of(
                Math.max(requestedPageable.getPageNumber(), 0),
                Math.min(Math.max(requestedPageable.getPageSize(), 1), 100));
        Page<ExpiringLotProjection> page = lotBalanceRepository.findExpiringLots(
                scope.tenantId(), branchId, businessDate, businessDate.plusDays(days),
                productId, locationId, pageable);
        return PageResponse.from(page, row -> expiringLot(row, businessDate));
    }

    private LookupScope requireScope(
            UUID branchId, UUID productId, UUID locationId, boolean productRequired) {
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
        if (productRequired || productId != null) {
            productRepository.findByTenantIdAndId(tenantId, productId)
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        }
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

    private static ExpiringLotDto expiringLot(
            ExpiringLotProjection row, LocalDate businessDate) {
        if (row.getReservedQuantity().compareTo(row.getQuantity()) > 0) {
            throw new IllegalStateException("El balance de lote tiene una reserva mayor que su existencia.");
        }
        return new ExpiringLotDto(
                row.getLotId(),
                row.getLotNumber(),
                row.getExpirationDate(),
                ChronoUnit.DAYS.between(businessDate, row.getExpirationDate()),
                row.getProductId(),
                row.getSku(),
                row.getProductName(),
                row.getBranchId(),
                row.getLocationId(),
                row.getQuantity(),
                row.getReservedQuantity(),
                row.getAvailableQuantity());
    }

    private record LookupScope(UUID tenantId) {}
}
