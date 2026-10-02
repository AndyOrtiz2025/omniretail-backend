package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.CreatePromotionRequest;
import com.omniretail.backend.catalog.dto.PromotionProductResponse;
import com.omniretail.backend.catalog.dto.PromotionResponse;
import com.omniretail.backend.catalog.dto.PromotionSummaryResponse;
import com.omniretail.backend.catalog.dto.UpdatePromotionRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import com.omniretail.backend.catalog.entity.PromotionProduct;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.PromotionProductRepository;
import com.omniretail.backend.catalog.repository.PromotionRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private static final BigDecimal MAX_FIXED_PRICE = new BigDecimal("9999999.99");

    private final PromotionRepository promotionRepository;
    private final PromotionProductRepository promotionProductRepository;
    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;
    private final CurrentUser currentUser;

    public PageResponse<PromotionSummaryResponse> list(UUID productId, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        if (productId != null && productRepository.findByTenantIdAndId(tenantId, productId).isEmpty()) {
            throw productNotFound();
        }
        Instant now = Instant.now();
        return PageResponse.from(
                productId == null ? promotionRepository.findByTenantId(tenantId, pageable)
                        : promotionRepository.findByTenantIdAndProductId(tenantId, productId, pageable),
                promotion -> PromotionSummaryResponse.from(promotion, now));
    }

    public PageResponse<PromotionSummaryResponse> list(Pageable pageable) { return list(null, pageable); }

    public PromotionResponse get(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        return response(requirePromotion(tenantId, id), tenantId, Instant.now());
    }

    @Transactional
    public PromotionResponse create(CreatePromotionRequest request) {
        AuthenticatedUser actor = currentUser.require();
        String name = normalizeName(request.name());
        validateDiscount(request.discountType(), request.discountValue());
        validateDates(request.startsAt(), request.endsAt());
        validateScope(actor.tenantId(), request.channels(), request.branchIds());
        List<UUID> productIds = requireDistinctProducts(request.productIds());
        List<Product> products = productRepository.findAllForUpdateByTenantIdAndIdIn(actor.tenantId(), productIds);
        if (products.size() != productIds.size()) {
            throw productNotFound();
        }
        if (products.stream().anyMatch(product -> product.getStatus() == ProductStatus.archived)) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVED", "Un producto archivado no puede agregarse a una promocion.");
        }
        validateNoOverlap(actor.tenantId(), null, productIds, request.channels(), request.branchIds(),
                request.startsAt(), request.endsAt());

        Promotion promotion = Promotion.builder()
                .name(name)
                .description(request.description())
                .discountType(request.discountType())
                .discountValue(request.discountValue())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .channels(List.copyOf(request.channels()))
                .untilStockEnds(Boolean.TRUE.equals(request.untilStockEnds()))
                .branchIds(List.copyOf(request.branchIds()))
                .status(PromotionStatus.active)
                .createdByUserId(actor.userId())
                .build();
        promotion.setTenantId(actor.tenantId());
        promotion = promotionRepository.saveAndFlush(promotion);

        UUID promotionId = promotion.getId();
        List<PromotionProduct> associations = productIds.stream().map(productId -> {
            PromotionProduct association = PromotionProduct.builder()
                    .promotionId(promotionId)
                    .productId(productId)
                    .build();
            association.setTenantId(actor.tenantId());
            return association;
        }).toList();
        promotionProductRepository.saveAllAndFlush(associations);
        return response(promotion, products, Instant.now());
    }

    @Transactional
    public PromotionResponse update(UUID id, UpdatePromotionRequest request) {
        AuthenticatedUser actor = currentUser.require();
        Promotion promotion = requirePromotionForUpdate(actor.tenantId(), id);
        Instant now = Instant.now();
        if (promotion.getStatus() != PromotionStatus.active
                || promotion.getEndsAt() != null && !now.isBefore(promotion.getEndsAt())) {
            throw BusinessException.conflict("PROMOTION_NOT_EDITABLE", "La promocion finalizada o cancelada no puede editarse.");
        }
        String name = normalizeName(request.name());
        validateDiscount(request.discountType(), request.discountValue());
        validateDates(request.startsAt(), request.endsAt());
        validateScope(actor.tenantId(), request.channels(), request.branchIds());
        List<UUID> productIds = requireDistinctProducts(request.productIds());
        List<Product> products = productRepository.findAllForUpdateByTenantIdAndIdIn(actor.tenantId(), productIds);
        if (products.size() != productIds.size()) throw productNotFound();
        if (products.stream().anyMatch(product -> product.getStatus() == ProductStatus.archived))
            throw BusinessException.conflict("PRODUCT_ARCHIVED", "Un producto archivado no puede agregarse a una promocion.");
        validateNoOverlap(actor.tenantId(), id, productIds, request.channels(), request.branchIds(),
                request.startsAt(), request.endsAt());
        promotion.setName(name); promotion.setDescription(request.description());
        promotion.setDiscountType(request.discountType()); promotion.setDiscountValue(request.discountValue());
        promotion.setStartsAt(request.startsAt()); promotion.setEndsAt(request.endsAt());
        promotion.setChannels(List.copyOf(request.channels()));
        promotion.setUntilStockEnds(Boolean.TRUE.equals(request.untilStockEnds()));
        promotion.setBranchIds(List.copyOf(request.branchIds()));
        promotion = promotionRepository.saveAndFlush(promotion);
        promotionProductRepository.deleteByTenantIdAndPromotionId(actor.tenantId(), id);
        promotionProductRepository.flush();
        UUID promotionId = promotion.getId();
        List<PromotionProduct> associations = productIds.stream().map(productId -> {
            PromotionProduct association = PromotionProduct.builder().promotionId(promotionId).productId(productId).build();
            association.setTenantId(actor.tenantId()); return association;
        }).toList();
        promotionProductRepository.saveAllAndFlush(associations);
        return response(promotion, products, Instant.now());
    }

    @Transactional
    public PromotionResponse end(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        Promotion promotion = requirePromotionForUpdate(actor.tenantId(), id);
        if (promotion.getStatus() == PromotionStatus.cancelled)
            throw BusinessException.conflict("PROMOTION_CANCELLED", "Una promocion cancelada no puede finalizarse.");
        if (promotion.getStatus() != PromotionStatus.ended) {
            promotion.end(Instant.now()); promotion = promotionRepository.saveAndFlush(promotion);
        }
        return response(promotion, actor.tenantId(), Instant.now());
    }

    @Transactional
    public PromotionResponse cancel(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        Promotion promotion = requirePromotionForUpdate(actor.tenantId(), id);
        if (promotion.getStatus() != PromotionStatus.cancelled) {
            promotion.cancel(actor.userId(), Instant.now());
            promotion = promotionRepository.saveAndFlush(promotion);
        }
        return response(promotion, actor.tenantId(), Instant.now());
    }

    private PromotionResponse response(Promotion promotion, UUID tenantId, Instant at) {
        List<UUID> ids = promotionProductRepository
                .findByTenantIdAndPromotionId(tenantId, promotion.getId()).stream()
                .map(PromotionProduct::getProductId)
                .toList();
        return response(promotion, productRepository.findByTenantIdAndIdIn(tenantId, ids), at);
    }

    private static PromotionResponse response(
            Promotion promotion, List<Product> products, Instant at) {
        List<PromotionProductResponse> productResponses = products.stream()
                .map(product -> new PromotionProductResponse(
                        product.getId(), product.getSku(), product.getName()))
                .sorted(Comparator.comparing(PromotionProductResponse::id))
                .toList();
        return PromotionResponse.from(promotion, at, productResponses);
    }

    private Promotion requirePromotion(UUID tenantId, UUID id) {
        return promotionRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PROMOTION_NOT_FOUND", "Promocion no encontrada."));
    }

    private static String normalizeName(String value) {
        if (value == null) {
            throw invalid("El nombre de la promocion es requerido.");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw invalid("El nombre de la promocion debe tener entre 1 y 200 caracteres.");
        }
        return normalized;
    }

    private static void validateDiscount(PromotionDiscountType type, BigDecimal value) {
        if (type == null || value == null || value.scale() > 2) {
            throw invalid("El descuento de la promocion no es valido.");
        }
        boolean valid = switch (type) {
            case percentage -> value.compareTo(BigDecimal.ZERO) > 0
                    && value.compareTo(new BigDecimal("100")) <= 0;
            case fixed_discount -> value.compareTo(BigDecimal.ZERO) > 0
                    && value.compareTo(MAX_FIXED_PRICE) <= 0;
            case fixed_price -> value.compareTo(BigDecimal.ZERO) >= 0
                    && value.compareTo(MAX_FIXED_PRICE) <= 0;
        };
        if (!valid) {
            throw invalid("El descuento de la promocion no es valido.");
        }
    }

    private static void validateDates(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt != null && !endsAt.isAfter(startsAt)) {
            throw invalid("La fecha final debe ser posterior a la fecha inicial.");
        }
    }

    private static List<UUID> requireDistinctProducts(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw invalid("La promocion debe incluir al menos un producto.");
        }
        Set<UUID> distinct = new HashSet<>();
        for (UUID productId : productIds) {
            if (productId == null) {
                throw invalid("Cada producto debe incluir un identificador.");
            }
            if (!distinct.add(productId)) {
                throw invalid("Un producto no puede aparecer mas de una vez.");
            }
        }
        return List.copyOf(productIds);
    }

    private Promotion requirePromotionForUpdate(UUID tenantId, UUID id) {
        return promotionRepository.findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PROMOTION_NOT_FOUND", "Promocion no encontrada."));
    }

    private void validateScope(UUID tenantId, List<String> channels, List<UUID> branchIds) {
        Set<String> allowed = Set.of("pos", "ecommerce", "mobileApp");
        if (channels == null || channels.isEmpty() || channels.stream().anyMatch(value -> !allowed.contains(value))
                || new HashSet<>(channels).size() != channels.size()) throw invalid("Los canales de la promocion no son validos.");
        if (branchIds == null || new HashSet<>(branchIds).size() != branchIds.size())
            throw invalid("Las sucursales de la promocion no son validas.");
        Set<UUID> active = branchRepository.findByTenantIdAndStatus(tenantId, BranchStatus.active).stream()
                .map(branch -> branch.getId()).collect(Collectors.toSet());
        if (!active.containsAll(branchIds)) throw new BusinessException(HttpStatus.NOT_FOUND,
                "BRANCH_NOT_FOUND", "Sucursal no encontrada o inactiva.");
    }

    private void validateNoOverlap(UUID tenantId, UUID currentId, List<UUID> productIds,
            List<String> channels, List<UUID> branchIds, Instant startsAt, Instant endsAt) {
        Set<UUID> products = Set.copyOf(productIds);
        for (Promotion existing : promotionRepository.findByTenantId(tenantId)) {
            if (java.util.Objects.equals(existing.getId(), currentId) || existing.getStatus() != PromotionStatus.active) continue;
            Set<UUID> existingProducts = promotionProductRepository
                    .findByTenantIdAndPromotionId(tenantId, existing.getId()).stream()
                    .map(PromotionProduct::getProductId).collect(Collectors.toSet());
            if (java.util.Collections.disjoint(products, existingProducts)
                    || java.util.Collections.disjoint(channels, existing.getChannels())) continue;
            boolean branchesOverlap = branchIds.isEmpty() || existing.getBranchIds().isEmpty()
                    || !java.util.Collections.disjoint(branchIds, existing.getBranchIds());
            Instant existingEnd = existing.getEndsAt();
            boolean datesOverlap = (endsAt == null || endsAt.isAfter(existing.getStartsAt()))
                    && (existingEnd == null || existingEnd.isAfter(startsAt));
            if (branchesOverlap && datesOverlap) throw BusinessException.conflict("PROMOTION_OVERLAP",
                    "La promocion coincide en fechas, canales y alcance con otra promocion.");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "PROMOTION_INVALID", message);
    }

    private static BusinessException productNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
}
