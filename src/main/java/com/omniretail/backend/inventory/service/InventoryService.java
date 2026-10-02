package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.LocationRepository;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.inventory.dto.InventoryBalanceResponse;
import com.omniretail.backend.inventory.dto.InventoryMovementDisplayType;
import com.omniretail.backend.inventory.dto.InventoryMovementListDto;
import com.omniretail.backend.inventory.dto.InventoryMovementPageResponse;
import com.omniretail.backend.inventory.dto.InventoryMovementSummaryDto;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementSpecifications;
import com.omniretail.backend.logistics.entity.Dispatch;
import com.omniretail.backend.logistics.repository.DispatchRepository;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleReturn;
import com.omniretail.backend.pos.repository.SaleRepository;
import com.omniretail.backend.pos.repository.SaleReturnRepository;
import com.omniretail.backend.purchasing.repository.GoodsReceiptRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final CurrentUser currentUser;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final BranchAccessResolver branchAccessResolver;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final SaleRepository saleRepository;
    private final SaleReturnRepository saleReturnRepository;
    private final DispatchRepository dispatchRepository;

    public PageResponse<InventoryBalanceResponse> listBalances(
            UUID branchId, Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);
        validateBranch(tenantId, branchId);
        requireBranchAccess(branchAccessResolver.resolve(actor), branchId);

        return PageResponse.from(
                inventoryBalanceRepository.findByTenantIdAndBranchId(
                        tenantId, branchId, pageable),
                InventoryBalanceResponse::from);
    }

    public InventoryMovementPageResponse searchMovements(
            UUID branchId,
            UUID productId,
            InventoryMovementType type,
            Instant from,
            Instant to,
            String search,
            InventoryMovementDisplayType displayType,
            Pageable pageable) {
        AuthenticatedUser actor = currentUser.require();
        UUID tenantId = actor.tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.inventory);

        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.badRequest(
                    "La fecha inicial no puede ser posterior a la fecha final.");
        }
        BranchAccess access = branchAccessResolver.resolve(actor);
        if (branchId != null) {
            validateBranch(tenantId, branchId);
            requireBranchAccess(access, branchId);
        }
        if (productId != null) {
            validateProduct(tenantId, productId);
        }

        Pageable safePageable = movementPageable(pageable);
        Collection<UUID> allowedBranchIds = null;
        if (branchId == null && !access.allBranches()) {
            allowedBranchIds = access.branchIds();
        }
        if (allowedBranchIds != null && allowedBranchIds.isEmpty()) {
            return new InventoryMovementPageResponse(
                    List.of(), safePageable.getPageNumber() + 1,
                    safePageable.getPageSize(), 0, 0, InventoryMovementSummaryDto.zero());
        }
        Specification<InventoryMovement> filters = InventoryMovementSpecifications.filtered(
                tenantId, branchId, allowedBranchIds, productId, type, from, to, search, displayType);
        Page<InventoryMovement> movements = inventoryMovementRepository.findAll(filters, safePageable);
        InventoryMovementSummaryDto summary = inventoryMovementRepository.summarize(filters);
        MovementContext context = context(tenantId, movements.getContent());
        return new InventoryMovementPageResponse(
                movements.getContent().stream().map(movement -> response(movement, context)).toList(),
                movements.getNumber() + 1,
                movements.getSize(),
                movements.getTotalElements(),
                movements.getTotalPages(),
                summary);
    }

    private MovementContext context(UUID tenantId, List<InventoryMovement> movements) {
        Set<UUID> branchIds = ids(movements.stream().map(InventoryMovement::getBranchId).toList());
        Set<UUID> productIds = ids(movements.stream().map(InventoryMovement::getProductId).toList());
        Set<UUID> userIds = ids(movements.stream().map(InventoryMovement::getPerformedByUserId).toList());
        Set<UUID> locationIds = new HashSet<>(ids(movements.stream().map(InventoryMovement::getFromLocationId).toList()));
        locationIds.addAll(ids(movements.stream().map(InventoryMovement::getToLocationId).toList()));

        Map<UUID, Branch> branches = indexBranches(branchRepository.findByTenantIdAndIdIn(tenantId, branchIds));
        Map<UUID, Product> products = indexProducts(productRepository.findByTenantIdAndIdIn(tenantId, productIds));
        Map<UUID, User> users = indexUsers(userRepository.findByTenantIdAndIdIn(tenantId, userIds));
        Map<UUID, Location> locations = indexLocations(locationRepository.findByTenantIdAndIdIn(tenantId, locationIds));
        Map<UUID, String> referenceLabels = referenceLabels(tenantId, movements);
        return new MovementContext(branches, products, users, locations, referenceLabels);
    }

    private Map<UUID, String> referenceLabels(UUID tenantId, List<InventoryMovement> movements) {
        Map<UUID, String> labels = new HashMap<>();
        Set<UUID> receiptIds = referenceIds(movements, Set.of("goods_receipt"), true);
        goodsReceiptRepository.findByTenantIdAndIdIn(tenantId, receiptIds)
                .forEach(receipt -> labels.put(receipt.getId(), receipt.getNumber()));

        Set<UUID> saleIds = referenceIds(movements, union(
                InventoryMovementDisplayType.SALE_REFERENCES,
                InventoryMovementDisplayType.VOID_REFERENCES), false);
        Set<UUID> returnIds = referenceIds(
                movements, InventoryMovementDisplayType.RETURN_REFERENCES, false);
        List<SaleReturn> returns = saleReturnRepository.findByTenantIdAndIdIn(tenantId, returnIds);
        Set<UUID> allSaleIds = new HashSet<>(saleIds);
        returns.stream().map(SaleReturn::getSaleId).forEach(allSaleIds::add);
        Map<UUID, Sale> sales = new HashMap<>();
        saleRepository.findByTenantIdAndIdIn(tenantId, allSaleIds)
                .forEach(sale -> sales.put(sale.getId(), sale));
        saleIds.forEach(id -> {
            Sale sale = sales.get(id);
            if (sale != null) labels.put(id, sale.getNumber());
        });
        returns.forEach(saleReturn -> {
            Sale sale = sales.get(saleReturn.getSaleId());
            if (sale != null) labels.put(saleReturn.getId(), sale.getNumber());
        });

        Set<UUID> dispatchIds = referenceIds(movements, Set.of("dispatch"), true);
        dispatchRepository.findByTenantIdAndIdIn(tenantId, dispatchIds).forEach(dispatch ->
                labels.put(dispatch.getId(), dispatchLabel(dispatch)));
        return labels;
    }

    private static InventoryMovementListDto response(InventoryMovement movement, MovementContext context) {
        Branch branch = context.branches().get(movement.getBranchId());
        Product product = context.products().get(movement.getProductId());
        User user = context.users().get(movement.getPerformedByUserId());
        Location from = context.locations().get(movement.getFromLocationId());
        Location to = context.locations().get(movement.getToLocationId());
        InventoryMovementDisplayType displayType = InventoryMovementDisplayType.from(movement);
        String referenceLabel = context.referenceLabels().get(movement.getReferenceId());
        if (referenceLabel == null && movement.getReferenceId() != null) {
            referenceLabel = movement.getReferenceId().toString();
        }
        return new InventoryMovementListDto(
                movement.getId(), movement.getTenantId(), movement.getBranchId(),
                branch == null ? null : branch.getName(), movement.getProductId(),
                product == null ? null : product.getName(), product == null ? null : product.getSku(),
                movement.getType(), displayType.jsonValue(), movement.getReason(), movement.getQuantity(),
                movement.getQuantityBefore(), movement.getQuantityAfter(), movement.getFromLocationId(),
                from == null ? null : from.getName(), movement.getToLocationId(),
                to == null ? null : to.getName(), movement.getReferenceType(), movement.getReferenceId(),
                referenceLabel, movement.getPerformedByUserId(), userLabel(user), movement.getCreatedAt());
    }

    private static Pageable movementPageable(Pageable pageable) {
        Set<String> supported = Set.of("createdAt", "quantity", "type", "productId", "branchId");
        pageable.getSort().forEach(order -> {
            if (!supported.contains(order.getProperty())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVENTORY_MOVEMENT_SORT_INVALID",
                        "El ordenamiento de movimientos no admite el campo solicitado.");
            }
        });
        Sort sort = pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                : pageable.getSort().and(Sort.by(Sort.Order.desc("id")));
        return PageRequest.of(
                Math.max(pageable.getPageNumber(), 0),
                Math.min(Math.max(pageable.getPageSize(), 1), 100),
                sort);
    }

    private static Set<UUID> ids(List<UUID> values) {
        Set<UUID> result = new HashSet<>();
        values.stream().filter(Objects::nonNull).forEach(result::add);
        return result;
    }

    private static Set<UUID> referenceIds(
            List<InventoryMovement> movements, Set<String> types, boolean ignoreCase) {
        Set<UUID> result = new HashSet<>();
        movements.stream()
                .filter(movement -> movement.getReferenceId() != null)
                .filter(movement -> types.stream().anyMatch(type -> ignoreCase
                        ? type.equalsIgnoreCase(movement.getReferenceType())
                        : type.equals(movement.getReferenceType())))
                .map(InventoryMovement::getReferenceId)
                .forEach(result::add);
        return result;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    private static Map<UUID, Branch> indexBranches(List<Branch> values) {
        Map<UUID, Branch> result = new HashMap<>();
        values.forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private static Map<UUID, Product> indexProducts(List<Product> values) {
        Map<UUID, Product> result = new HashMap<>();
        values.forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private static Map<UUID, User> indexUsers(List<User> values) {
        Map<UUID, User> result = new HashMap<>();
        values.forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private static Map<UUID, Location> indexLocations(List<Location> values) {
        Map<UUID, Location> result = new HashMap<>();
        values.forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private static String userLabel(User user) {
        if (user == null) return null;
        if (user.getEmployeeCode() != null && !user.getEmployeeCode().isBlank()) {
            return user.getName() + " (" + user.getEmployeeCode() + ")";
        }
        return user.getName();
    }

    private static String dispatchLabel(Dispatch dispatch) {
        return dispatch.getTrackingNumber() == null || dispatch.getTrackingNumber().isBlank()
                ? dispatch.getId().toString()
                : dispatch.getTrackingNumber();
    }

    private record MovementContext(
            Map<UUID, Branch> branches,
            Map<UUID, Product> products,
            Map<UUID, User> users,
            Map<UUID, Location> locations,
            Map<UUID, String> referenceLabels) {}

    private void validateBranch(UUID tenantId, UUID branchId) {
        branchRepository.findByTenantIdAndId(tenantId, branchId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "BRANCH_NOT_FOUND",
                        "Sucursal no encontrada."));
    }

    private void validateProduct(UUID tenantId, UUID productId) {
        productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "PRODUCT_NOT_FOUND",
                        "Producto no encontrado."));
    }

    private static void requireBranchAccess(BranchAccess access, UUID branchId) {
        if (!access.allows(branchId)) {
            throw BusinessException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tienes acceso a esta sucursal.");
        }
    }
}
