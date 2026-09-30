package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.CreatePromotionRequest;
import com.omniretail.backend.catalog.dto.PromotionProductResponse;
import com.omniretail.backend.catalog.dto.PromotionResponse;
import com.omniretail.backend.catalog.dto.PromotionSummaryResponse;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final CurrentUser currentUser;

    public PageResponse<PromotionSummaryResponse> list(Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Instant now = Instant.now();
        return PageResponse.from(
                promotionRepository.findByTenantId(tenantId, pageable),
                promotion -> PromotionSummaryResponse.from(promotion, now));
    }

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
        List<UUID> productIds = requireDistinctProducts(request.productIds());
        List<Product> products = productRepository.findByTenantIdAndIdIn(actor.tenantId(), productIds);
        if (products.size() != productIds.size()) {
            throw productNotFound();
        }
        if (products.stream().anyMatch(product -> product.getStatus() == ProductStatus.archived)) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVED", "Un producto archivado no puede agregarse a una promocion.");
        }

        Promotion promotion = Promotion.builder()
                .name(name)
                .discountType(request.discountType())
                .discountValue(request.discountValue())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
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
    public PromotionResponse cancel(UUID id) {
        AuthenticatedUser actor = currentUser.require();
        Promotion promotion = requirePromotion(actor.tenantId(), id);
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
        boolean valid = type == PromotionDiscountType.percentage
                ? value.compareTo(BigDecimal.ZERO) > 0 && value.compareTo(new BigDecimal("100")) <= 0
                : value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(MAX_FIXED_PRICE) <= 0;
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

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "PROMOTION_INVALID", message);
    }

    private static BusinessException productNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
}
