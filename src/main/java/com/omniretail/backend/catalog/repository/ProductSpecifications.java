package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.dto.ProductChannel;
import com.omniretail.backend.catalog.dto.ProductPromotionFilter;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionProduct;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class ProductSpecifications {

    private ProductSpecifications() {}

    public static Specification<Product> filtered(
            UUID tenantId,
            String search,
            ProductStatus status,
            ProductType productType,
            UUID categoryId,
            List<ProductChannel> channels,
            ProductPromotionFilter promotion,
            Instant now) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            String term = normalize(search);
            if (term != null) {
                String pattern = "%" + term + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("sku")), pattern),
                        cb.like(cb.lower(root.get("brand")), pattern),
                        cb.like(cb.lower(root.get("barcode")), pattern)));
            }
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (productType != null) predicates.add(cb.equal(root.get("productType"), productType));
            if (categoryId != null) predicates.add(cb.equal(root.get("categoryId"), categoryId));
            if (channels != null && !channels.isEmpty()) {
                predicates.add(cb.or(channels.stream()
                        .distinct()
                        .map(channel -> cb.isTrue(root.get(channelField(channel))))
                        .toArray(Predicate[]::new)));
            }
            if (promotion != null && promotion != ProductPromotionFilter.all) {
                Subquery<Integer> activePromotion = query.subquery(Integer.class);
                Root<PromotionProduct> association = activePromotion.from(PromotionProduct.class);
                Root<Promotion> promotionRoot = activePromotion.from(Promotion.class);
                activePromotion.select(cb.literal(1)).where(
                        cb.equal(association.get("tenantId"), tenantId),
                        cb.equal(association.get("productId"), root.get("id")),
                        cb.equal(association.get("promotionId"), promotionRoot.get("id")),
                        cb.equal(promotionRoot.get("tenantId"), tenantId),
                        cb.equal(promotionRoot.get("status"), PromotionStatus.active),
                        cb.lessThanOrEqualTo(promotionRoot.get("startsAt"), now),
                        cb.or(
                                cb.isNull(promotionRoot.get("endsAt")),
                                cb.greaterThan(promotionRoot.get("endsAt"), now)));
                Predicate exists = cb.exists(activePromotion);
                predicates.add(promotion == ProductPromotionFilter.with ? exists : cb.not(exists));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String channelField(ProductChannel channel) {
        return switch (channel) {
            case pos -> "channelPos";
            case ecommerce -> "channelEcommerce";
            case mobileApp -> "channelMobileApp";
        };
    }
}
