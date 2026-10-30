package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.dto.ResolvedTierPrice;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import com.omniretail.backend.catalog.repository.ProductPromotion;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductSalesPriceTierRepository;
import com.omniretail.backend.catalog.repository.PromotionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Tope de ids por consulta: mantiene el IN lejos del limite de parametros de PostgreSQL. */
    static final int PRODUCT_BATCH_SIZE = 1000;

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
        requireTenantProduct(tenantId, product);
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
        return bestPrice(
                basePrice,
                promotionRepository.findApplicable(tenantId, product.getId(), PromotionStatus.active, at),
                channel,
                branchId);
    }

    /**
     * Precio vigente por unidad de varios productos con la misma regla que
     * {@link #resolveEffectivePrice(UUID, Product, Instant, String, UUID)}, pero con una consulta de
     * promociones por lote de productos en vez de una por producto.
     */
    public Map<UUID, ResolvedProductPrice> resolveEffectivePrices(
            UUID tenantId, Collection<Product> products, Instant at, String channel, UUID branchId) {
        if (products.isEmpty()) return Map.of();
        products.forEach(product -> requireTenantProduct(tenantId, product));
        List<UUID> productIds = products.stream().map(Product::getId).distinct().toList();
        // Cada lista conserva el orden de la consulta (startsAt desc, id asc), igual que findApplicable.
        Map<UUID, List<Promotion>> promotionsByProduct = new HashMap<>();
        for (int from = 0; from < productIds.size(); from += PRODUCT_BATCH_SIZE) {
            List<UUID> batch = productIds.subList(
                    from, Math.min(from + PRODUCT_BATCH_SIZE, productIds.size()));
            for (ProductPromotion match : promotionRepository.findApplicableByProductIds(
                    tenantId, batch, PromotionStatus.active, at)) {
                promotionsByProduct.computeIfAbsent(match.productId(), id -> new ArrayList<>())
                        .add(match.promotion());
            }
        }
        Map<UUID, ResolvedProductPrice> prices = new HashMap<>();
        for (Product product : products) {
            prices.put(product.getId(), bestPrice(
                    money(product.getSalePrice()),
                    promotionsByProduct.getOrDefault(product.getId(), List.of()),
                    channel,
                    branchId));
        }
        return prices;
    }

    /**
     * Precio vigente de cada escalón activo, ordenado por cantidad mínima. Usa la misma resolución
     * que el cobro, así quien muestre estos precios muestra exactamente lo que se cobrará.
     */
    public List<ResolvedTierPrice> resolveTierPrices(
            UUID tenantId, Product product, Instant at, String channel, UUID branchId) {
        return priceTierRepository.findByTenantIdAndProductIdOrderByMinQuantityAsc(
                        tenantId, product.getId()).stream()
                .filter(tier -> Boolean.TRUE.equals(tier.getActive()))
                .map(tier -> new ResolvedTierPrice(
                        tier.getMinQuantity(),
                        resolveEffectivePrice(
                                tenantId, product, at, channel, branchId,
                                BigDecimal.valueOf(tier.getMinQuantity()))))
                .toList();
    }

    private static void requireTenantProduct(UUID tenantId, Product product) {
        if (product == null
                || product.getTenantId() == null
                || !product.getTenantId().equals(tenantId)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
        }
    }

    /** Gana el menor precio final; ante empate se queda la primera promocion en el orden recibido. */
    private static ResolvedProductPrice bestPrice(
            BigDecimal basePrice, List<Promotion> promotions, String channel, UUID branchId) {
        ResolvedProductPrice best = new ResolvedProductPrice(
                basePrice, basePrice, BigDecimal.ZERO.setScale(2), null);

        // La query ya ordena empates por startsAt mas reciente y luego UUID ascendente.
        for (Promotion promotion : promotions) {
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
