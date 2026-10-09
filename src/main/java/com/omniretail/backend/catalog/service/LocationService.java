package com.omniretail.backend.catalog.service;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.LocationCreateRequest;
import com.omniretail.backend.catalog.dto.LocationResponse;
import com.omniretail.backend.catalog.dto.LocationUpdateRequest;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.LocationStatus;
import com.omniretail.backend.catalog.entity.LocationType;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.ProductInventorySettingsRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

    private static final String LOCATION_NOT_FOUND = "Ubicación no encontrada.";
    private static final String HIERARCHY_INVALID =
            "La jerarquía de la ubicación no es válida.";

    private final LocationRepository locationRepository;
    private final BranchRepository branchRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final ProductInventorySettingsRepository productInventorySettingsRepository;
    private final BusinessConfigService businessConfigService;
    private final BranchAccessResolver branchAccessResolver;
    private final CurrentUser currentUser;

    public PageResponse<LocationResponse> list(
            UUID branchId,
            UUID parentId,
            LocationType type,
            LocationStatus status,
            Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        ensureMultipleLocationsEnabled();
        BranchAccess access = branchAccessResolver.resolve(actor);

        Page<Location> locations;
        if (branchId != null) {
            requireBranch(tenantId, branchId);
            ensureBranchAccess(access, branchId);
            locations = locationRepository.findAllFiltered(
                    tenantId, branchId, parentId, type, status, pageable);
        } else if (access.allBranches()) {
            locations = locationRepository.findAllFiltered(
                    tenantId, null, parentId, type, status, pageable);
        } else if (access.branchIds().isEmpty()) {
            locations = Page.empty(pageable);
        } else {
            locations = locationRepository.findAllFilteredForBranches(
                    tenantId, access.branchIds(), parentId, type, status, pageable);
        }
        return PageResponse.from(locations, LocationResponse::from);
    }

    public LocationResponse getById(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        ensureMultipleLocationsEnabled();
        Location location = requireLocation(actor.tenantId(), id);
        ensureBranchAccess(branchAccessResolver.resolve(actor), location.getBranchId());
        return LocationResponse.from(location);
    }

    @Transactional
    public LocationResponse create(LocationCreateRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        ensureMultipleLocationsEnabled();
        requireActiveBranch(tenantId, request.branchId());
        ensureBranchAccess(branchAccessResolver.resolve(actor), request.branchId());

        String code = normalizeCode(request.code());
        if (locationRepository.existsByTenantIdAndBranchIdAndCode(
                tenantId, request.branchId(), code)) {
            throw BusinessException.conflict(
                    "LOCATION_CODE_CONFLICT",
                    "Ya existe una ubicación con ese código en la sucursal.");
        }
        validateParentForCreate(
                tenantId, request.branchId(), request.parentId(), request.type());

        Location location = Location.builder()
                .branchId(request.branchId())
                .parentId(request.parentId())
                .code(code)
                .name(request.name().trim())
                .type(request.type())
                .status(request.status() == null ? LocationStatus.active : request.status())
                .build();
        location.setTenantId(tenantId);
        return LocationResponse.from(locationRepository.saveAndFlush(location));
    }

    @Transactional
    public LocationResponse update(UUID id, LocationUpdateRequest request) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        ensureMultipleLocationsEnabled();
        Location location = requireLocation(tenantId, id);
        ensureBranchAccess(branchAccessResolver.resolve(actor), location.getBranchId());
        if (location.getId().equals(location.getParentId())) {
            throw hierarchyInvalid();
        }

        if (request.status() == LocationStatus.active
                && location.getStatus() != LocationStatus.active) {
            validateReactivation(tenantId, location);
        } else if (request.status() == LocationStatus.archived
                && location.getStatus() != LocationStatus.archived) {
            validateCanArchive(tenantId, location);
        } else if (request.status() == LocationStatus.inactive
                && location.getStatus() == LocationStatus.active) {
            validateNotAssignedToProducts(tenantId, location);
        }

        location.setName(request.name().trim());
        location.setStatus(request.status());
        return LocationResponse.from(locationRepository.saveAndFlush(location));
    }

    @Transactional
    public void archive(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        ensureMultipleLocationsEnabled();
        Location location = requireLocation(tenantId, id);
        ensureBranchAccess(branchAccessResolver.resolve(actor), location.getBranchId());
        if (location.getStatus() == LocationStatus.archived) {
            return;
        }
        validateCanArchive(tenantId, location);
        location.setStatus(LocationStatus.archived);
        locationRepository.saveAndFlush(location);
    }

    private void ensureMultipleLocationsEnabled() {
        if (!businessConfigService.getConfig().supportsMultipleLocations()) {
            throw BusinessException.forbidden(
                    "BUSINESS_CAPABILITY_DISABLED",
                    "La gestión de ubicaciones no está habilitada para este negocio.");
        }
    }

    private Location requireLocation(UUID tenantId, UUID id) {
        return locationRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "LOCATION_NOT_FOUND",
                        LOCATION_NOT_FOUND));
    }

    private Branch requireBranch(UUID tenantId, UUID branchId) {
        return branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "BRANCH_NOT_FOUND",
                        "Sucursal no encontrada."));
    }

    private Branch requireActiveBranch(UUID tenantId, UUID branchId) {
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(LocationService::branchNotFoundOrInactive);
        if (branch.getStatus() != BranchStatus.active) {
            throw branchNotFoundOrInactive();
        }
        return branch;
    }

    private void validateParentForCreate(
            UUID tenantId,
            UUID branchId,
            UUID parentId,
            LocationType type) {
        if (type == LocationType.warehouse) {
            if (parentId != null) {
                throw hierarchyInvalid();
            }
            return;
        }
        if (parentId == null) {
            throw hierarchyInvalid();
        }

        Location parent = locationRepository.findByTenantIdAndId(tenantId, parentId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "LOCATION_PARENT_NOT_FOUND",
                        "Ubicación padre no encontrada."));
        if (!parent.getBranchId().equals(branchId)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_HIERARCHY_INVALID",
                    "La ubicación padre debe pertenecer a la misma sucursal.");
        }
        if (parent.getStatus() != LocationStatus.active) {
            throw parentInactive();
        }
        if (!isValidParentType(type, parent.getType())) {
            throw hierarchyInvalid();
        }
    }

    private void validateReactivation(UUID tenantId, Location location) {
        Branch branch = requireBranch(tenantId, location.getBranchId());
        if (branch.getStatus() != BranchStatus.active) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_BRANCH_INACTIVE",
                    "La sucursal debe estar activa para reactivar la ubicación.");
        }
        if (location.getParentId() == null) {
            return;
        }
        Location parent = locationRepository
                .findByTenantIdAndId(tenantId, location.getParentId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "LOCATION_PARENT_NOT_FOUND",
                        "Ubicación padre no encontrada."));
        if (parent.getStatus() != LocationStatus.active) {
            throw parentInactive();
        }
    }

    private void validateCanArchive(UUID tenantId, Location location) {
        if (locationRepository.existsByTenantIdAndParentIdAndStatus(
                tenantId, location.getId(), LocationStatus.active)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_HAS_ACTIVE_CHILDREN",
                    "La ubicación tiene ubicaciones hijas activas.");
        }
        if (inventoryBalanceRepository.existsPositiveStockByTenantIdAndLocationId(
                tenantId, location.getId())) {
            throw BusinessException.conflict(
                    "LOCATION_HAS_STOCK",
                    "La ubicación contiene inventario y no puede archivarse.");
        }
        validateNotAssignedToProducts(tenantId, location);
    }

    /**
     * Una ubicacion asignada como ubicacion operativa de algun producto deja de aceptar entradas y ventas de
     * esos productos en cuanto sale de "active"; por eso ni se archiva ni se inactiva mientras siga asignada.
     */
    private void validateNotAssignedToProducts(UUID tenantId, Location location) {
        if (productInventorySettingsRepository.existsByTenantIdAndDefaultLocationId(
                tenantId, location.getId())) {
            throw BusinessException.conflict(
                    "LOCATION_ASSIGNED_TO_PRODUCTS",
                    "La ubicación está asignada a productos y no puede archivarse ni inactivarse. "
                            + "Asigne otra ubicación a esos productos primero.");
        }
    }

    private static boolean isValidParentType(
            LocationType childType, LocationType parentType) {
        return switch (childType) {
            case warehouse -> false;
            case aisle -> parentType == LocationType.warehouse;
            case shelf -> parentType == LocationType.warehouse
                    || parentType == LocationType.aisle;
            case level -> parentType == LocationType.shelf;
        };
    }

    private static String normalizeCode(String value) {
        String code = value.trim().replaceAll("\\s+", "-").toUpperCase(Locale.ROOT);
        if (code.isEmpty() || code.length() > 50) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_CODE_INVALID",
                    "El código de la ubicación no es válido.");
        }
        return code;
    }

    private static void ensureBranchAccess(BranchAccess access, UUID branchId) {
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED",
                    "No tienes acceso a esta sucursal.");
        }
    }

    private static BusinessException branchNotFoundOrInactive() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "BRANCH_NOT_FOUND",
                "Sucursal no encontrada o inactiva.");
    }

    private static BusinessException hierarchyInvalid() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "LOCATION_HIERARCHY_INVALID",
                HIERARCHY_INVALID);
    }

    private static BusinessException parentInactive() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "LOCATION_PARENT_INACTIVE",
                "La ubicación padre debe estar activa.");
    }
}
