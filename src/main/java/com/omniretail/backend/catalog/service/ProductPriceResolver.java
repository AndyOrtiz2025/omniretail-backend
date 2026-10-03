package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductSalesPriceTierRepository;
import com.omniretail.backend.catalog.repository.PromotionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductPriceResolver {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final ProductRepository productRepository;
    private final PromotionRepository promotionRepository;
    private final ProductSalesPriceTierRepository priceTierRepository;

    public ResolvedProductPrice resolveEffectivePrice(UUID tenantId, UUID productId, Instant at) {
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
        return resolveEffectivePrice(tenantId, product, at);
    }

    public ResolvedProductPrice resolveEffectivePrice(UUID tenantId, Product product, Instant at) {
        return resolveEffectivePrice(tenantId, product, at, null, null);
    }

    public ResolvedProductPrice resolveEffectivePrice(
            UUID tenantId, Product product, Instant at, String channel, UUID branchId) {
        return resolveEffectivePrice(tenantId, product, at, channel, branchId, null);
    }

    public ResolvedProductPrice resolveEffectivePrice(
            UUID tenantId,
            Product product,
            Instant at,
            String channel,
            UUID branchId,
            BigDecimal commercialQuantity) {
        if (product == null
                || product.getTenantId() == null
                || !product.getTenantId().equals(tenantId)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
        }
        BigDecimal basePrice = commercialQuantity == null
                ? money(product.getSalePrice())
                : priceTierRepository.findByTenantIdAndProductIdOrderByMinQuantityAsc(
                                tenantId, product.getId()).stream()
                        .filter(tier -> Boolean.TRUE.equals(tier.getActive()))
                        .filter(tier -> commercialQuantity.compareTo(
                                BigDecimal.valueOf(tier.getMinQuantity())) >= 0)
                        .reduce((first, second) -> second)
                        .map(tier -> money(tier.getUnitPrice()))
                        .orElseGet(() -> money(product.getSalePrice()));
        ResolvedProductPrice best = new ResolvedProductPrice(
                basePrice, basePrice, BigDecimal.ZERO.setScale(2), null);

        // La query ya ordena empates por startsAt mas reciente y luego UUID ascendente.
        for (Promotion promotion : promotionRepository.findApplicable(
                tenantId, product.getId(), PromotionStatus.active, at)) {
            if (channel != null && (promotion.getChannels() == null || !promotion.getChannels().contains(channel))) continue;
            if (promotion.getBranchIds() != null && !promotion.getBranchIds().isEmpty()
                    && (branchId == null || !promotion.getBranchIds().contains(branchId))) continue;
            ResolvedProductPrice candidate = calculate(basePrice, promotion);
            if (candidate.effectivePrice().compareTo(best.effectivePrice()) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static ResolvedProductPrice calculate(BigDecimal basePrice, Promotion promotion) {
        BigDecimal effectivePrice;
        if (promotion.getDiscountType() == PromotionDiscountType.percentage) {
            BigDecimal discount = basePrice.multiply(promotion.getDiscountValue())
                    .divide(ONE_HUNDRED)
                    .setScale(2, RoundingMode.HALF_UP);
            effectivePrice = money(basePrice.subtract(discount));
        } else if (promotion.getDiscountType() == PromotionDiscountType.fixed_price) {
            effectivePrice = money(promotion.getDiscountValue());
            if (effectivePrice.compareTo(basePrice) > 0) {
                effectivePrice = basePrice;
            }
        } else {
            effectivePrice = money(basePrice.subtract(promotion.getDiscountValue()));
        }
        if (effectivePrice.signum() < 0) {
            effectivePrice = BigDecimal.ZERO.setScale(2);
        }
        if (effectivePrice.compareTo(basePrice) >= 0) {
            return new ResolvedProductPrice(
                    basePrice, basePrice, BigDecimal.ZERO.setScale(2), null);
        }
        return new ResolvedProductPrice(
                basePrice,
                effectivePrice,
                money(basePrice.subtract(effectivePrice)),
                promotion.getId());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
