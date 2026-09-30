package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.Promotion;
import com.omniretail.backend.catalog.entity.PromotionDiscountType;
import com.omniretail.backend.catalog.entity.PromotionStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.PromotionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductPriceResolverTest {

    @Mock private ProductRepository productRepository;
    @Mock private PromotionRepository promotionRepository;
    @InjectMocks private ProductPriceResolver resolver;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-09-29T12:00:00Z");
    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .sku("SKU-1")
                .name("Producto")
                .salePrice(new BigDecimal("99.99"))
                .build();
        product.setTenantId(tenantId);
        ReflectionTestUtils.setField(product, "id", productId);
        lenient().when(productRepository.findByTenantIdAndId(tenantId, productId))
                .thenReturn(Optional.of(product));
    }

    @Test
    void returnsBasePriceWhenNoPromotionApplies() {
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of());

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.basePrice()).isEqualByComparingTo("99.99");
        assertThat(result.effectivePrice()).isEqualByComparingTo("99.99");
        assertThat(result.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(result.promotionId()).isNull();
    }

    @Test
    void calculatesPercentageUsingProjectMoneyRounding() {
        Promotion promotion = promotion(PromotionDiscountType.percentage, "12.50", at.minusSeconds(60));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(promotion));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.discountAmount()).isEqualByComparingTo("12.50");
        assertThat(result.effectivePrice()).isEqualByComparingTo("87.49");
        assertThat(result.promotionId()).isEqualTo(promotion.getId());
    }

    @Test
    void calculatesFixedPrice() {
        Promotion promotion = promotion(PromotionDiscountType.fixed_price, "59.90", at.minusSeconds(60));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(promotion));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.effectivePrice()).isEqualByComparingTo("59.90");
        assertThat(result.discountAmount()).isEqualByComparingTo("40.09");
    }

    @Test
    void fixedPriceAboveBaseNeverIncreasesPrice() {
        Promotion promotion = promotion(PromotionDiscountType.fixed_price, "120.00", at.minusSeconds(60));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(promotion));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.effectivePrice()).isEqualByComparingTo("99.99");
        assertThat(result.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(result.promotionId()).isNull();
    }

    @Test
    void fixedPriceEqualToBaseIsNotApplied() {
        Promotion promotion = promotion(PromotionDiscountType.fixed_price, "99.99", at.minusSeconds(60));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(promotion));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.effectivePrice()).isEqualByComparingTo("99.99");
        assertThat(result.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(result.promotionId()).isNull();
    }

    @Test
    void percentageRoundedToZeroIsNotApplied() {
        product.setSalePrice(new BigDecimal("0.01"));
        Promotion promotion = promotion(PromotionDiscountType.percentage, "0.01", at.minusSeconds(60));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(promotion));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, product, at);

        assertThat(result.effectivePrice()).isEqualByComparingTo("0.01");
        assertThat(result.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(result.promotionId()).isNull();
    }

    @Test
    void overlappingPromotionsSelectLowestEffectivePrice() {
        Promotion tenPercent = promotion(PromotionDiscountType.percentage, "10.00", at.minusSeconds(30));
        Promotion fixed = promotion(PromotionDiscountType.fixed_price, "70.00", at.minusSeconds(120));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(tenPercent, fixed));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.effectivePrice()).isEqualByComparingTo("70.00");
        assertThat(result.promotionId()).isEqualTo(fixed.getId());
    }

    @Test
    void deterministicTieKeepsFirstRepositoryCandidate() {
        Promotion newest = promotion(PromotionDiscountType.fixed_price, "80.00", at.minusSeconds(30));
        Promotion older = promotion(PromotionDiscountType.fixed_price, "80.00", at.minusSeconds(120));
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of(newest, older));

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, productId, at);

        assertThat(result.promotionId()).isEqualTo(newest.getId());
    }

    @Test
    void usesOnlyExplicitTenantScope() {
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of());

        resolver.resolveEffectivePrice(tenantId, productId, at);

        verify(productRepository).findByTenantIdAndId(tenantId, productId);
        verify(promotionRepository).findApplicable(tenantId, productId, PromotionStatus.active, at);
    }

    @Test
    void productOverloadDoesNotReloadProduct() {
        when(promotionRepository.findApplicable(tenantId, productId, PromotionStatus.active, at))
                .thenReturn(List.of());

        ResolvedProductPrice result = resolver.resolveEffectivePrice(tenantId, product, at);

        assertThat(result.basePrice()).isEqualByComparingTo("99.99");
        verifyNoInteractions(productRepository);
        verify(promotionRepository).findApplicable(tenantId, productId, PromotionStatus.active, at);
    }

    @Test
    void productOverloadRejectsNullAndCrossTenantProduct() {
        assertThatThrownBy(() -> resolver.resolveEffectivePrice(tenantId, (Product) null, at))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));

        product.setTenantId(UUID.randomUUID());
        assertThatThrownBy(() -> resolver.resolveEffectivePrice(tenantId, product, at))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
        verifyNoInteractions(promotionRepository);
    }

    @Test
    void crossTenantProductIsNotFound() {
        UUID otherTenant = UUID.randomUUID();
        when(productRepository.findByTenantIdAndId(otherTenant, productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveEffectivePrice(otherTenant, productId, at))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    private Promotion promotion(PromotionDiscountType type, String value, Instant startsAt) {
        Promotion promotion = Promotion.builder()
                .name("Oferta")
                .discountType(type)
                .discountValue(new BigDecimal(value))
                .startsAt(startsAt)
                .status(PromotionStatus.active)
                .createdByUserId(UUID.randomUUID())
                .build();
        promotion.setTenantId(tenantId);
        ReflectionTestUtils.setField(promotion, "id", UUID.randomUUID());
        return promotion;
    }
}
