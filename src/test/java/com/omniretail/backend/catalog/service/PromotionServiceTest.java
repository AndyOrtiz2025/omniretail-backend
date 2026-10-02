package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.CreatePromotionRequest;
import com.omniretail.backend.catalog.dto.PromotionLifecycleStatus;
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
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock private PromotionRepository promotionRepository;
    @Mock private PromotionProductRepository promotionProductRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private CurrentUser currentUser;
    @InjectMocks private PromotionService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final Instant startsAt = Instant.parse("2026-10-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(currentUser.require()).thenReturn(new AuthenticatedUser(
                userId, tenantId, UserType.employee, null, null, UUID.randomUUID()));
    }

    @Test
    void createsPercentagePromotionAndNormalizesName() {
        Product product = product(productId, ProductStatus.published);
        when(productRepository.findAllForUpdateByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of(product));
        when(promotionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Promotion promotion = invocation.getArgument(0);
            ReflectionTestUtils.setField(promotion, "id", UUID.randomUUID());
            return promotion;
        });

        var result = service.create(request(
                "  Oferta de octubre  ", PromotionDiscountType.percentage,
                new BigDecimal("12.50"), List.of(productId), startsAt.plusSeconds(3600)));

        assertThat(result.name()).isEqualTo("Oferta de octubre");
        assertThat(result.discountType()).isEqualTo(PromotionDiscountType.percentage);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PromotionProduct>> associations = ArgumentCaptor.forClass(List.class);
        verify(promotionProductRepository).saveAllAndFlush(associations.capture());
        assertThat(associations.getValue()).singleElement().satisfies(association -> {
            assertThat(association.getTenantId()).isEqualTo(tenantId);
            assertThat(association.getProductId()).isEqualTo(productId);
        });
    }

    @Test
    void createsFixedPricePromotion() {
        Product product = product(productId, ProductStatus.published);
        when(productRepository.findAllForUpdateByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of(product));
        when(promotionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Promotion promotion = invocation.getArgument(0);
            ReflectionTestUtils.setField(promotion, "id", UUID.randomUUID());
            return promotion;
        });

        var result = service.create(request(
                "Precio especial", PromotionDiscountType.fixed_price,
                new BigDecimal("0.00"), List.of(productId), null));

        assertThat(result.discountValue()).isEqualByComparingTo("0.00");
        assertThat(result.products()).singleElement()
                .extracting(productResponse -> productResponse.id())
                .isEqualTo(productId);
    }

    @Test
    void rejectsInvalidPercentage() {
        assertInvalid(request("Oferta", PromotionDiscountType.percentage,
                BigDecimal.ZERO, List.of(productId), null));
        assertInvalid(request("Oferta", PromotionDiscountType.percentage,
                new BigDecimal("100.01"), List.of(productId), null));
        assertInvalid(request("Oferta", PromotionDiscountType.percentage,
                new BigDecimal("10.001"), List.of(productId), null));
    }

    @Test
    void rejectsInvalidFixedPrice() {
        assertInvalid(request("Oferta", PromotionDiscountType.fixed_price,
                new BigDecimal("-0.01"), List.of(productId), null));
        assertInvalid(request("Oferta", PromotionDiscountType.fixed_price,
                new BigDecimal("10000000.00"), List.of(productId), null));
    }

    @Test
    void rejectsZeroFixedDiscount() {
        assertInvalid(request("Oferta", PromotionDiscountType.fixed_discount,
                BigDecimal.ZERO, List.of(productId), null));
    }

    @Test
    void rejectsInvalidDates() {
        assertInvalid(request("Oferta", PromotionDiscountType.percentage,
                BigDecimal.TEN, List.of(productId), startsAt));
        assertInvalid(new CreatePromotionRequest(
                "Oferta", PromotionDiscountType.percentage, BigDecimal.TEN,
                null, null, List.of(productId)));
    }

    @Test
    void rejectsBlankNullAndNormalizedNameLongerThanLimit() {
        assertInvalid(request("   ", PromotionDiscountType.percentage,
                BigDecimal.TEN, List.of(productId), null));
        assertInvalid(request(null, PromotionDiscountType.percentage,
                BigDecimal.TEN, List.of(productId), null));
        assertInvalid(request("  " + "x".repeat(201) + "  ", PromotionDiscountType.percentage,
                BigDecimal.TEN, List.of(productId), null));
    }

    @Test
    void rejectsEmptyAndDuplicateProductLists() {
        assertInvalid(request("Oferta", PromotionDiscountType.percentage,
                BigDecimal.TEN, List.of(), null));
        assertInvalid(request("Oferta", PromotionDiscountType.percentage,
                BigDecimal.TEN, List.of(productId, productId), null));
    }

    @Test
    void rejectsArchivedProduct() {
        when(productRepository.findAllForUpdateByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of(product(productId, ProductStatus.archived)));

        assertThatThrownBy(() -> service.create(request(
                        "Oferta", PromotionDiscountType.percentage,
                        BigDecimal.TEN, List.of(productId), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_ARCHIVED"));
    }

    @Test
    void treatsCrossTenantProductAsNotFound() {
        when(productRepository.findAllForUpdateByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request(
                        "Oferta", PromotionDiscountType.percentage,
                        BigDecimal.TEN, List.of(productId), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    @Test
    void listsTenantPromotionsWithDerivedStatus() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(promotionRepository.findByTenantId(tenantId, pageable))
                .thenReturn(new PageImpl<>(List.of(promotion(PromotionStatus.active)), pageable, 1));

        var result = service.list(pageable);

        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isOne();
        verify(promotionRepository).findByTenantId(tenantId, pageable);
    }

    @Test
    void derivesScheduledActiveEndedAndCancelledStatuses() {
        Promotion promotion = promotion(PromotionStatus.active);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plusSeconds(3600);
        ReflectionTestUtils.setField(promotion, "startsAt", start);
        ReflectionTestUtils.setField(promotion, "endsAt", end);

        assertThat(PromotionSummaryResponse.lifecycle(promotion, start.minusSeconds(1)))
                .isEqualTo(PromotionLifecycleStatus.scheduled);
        assertThat(PromotionSummaryResponse.lifecycle(promotion, start))
                .isEqualTo(PromotionLifecycleStatus.active);
        assertThat(PromotionSummaryResponse.lifecycle(promotion, end))
                .isEqualTo(PromotionLifecycleStatus.ended);
        ReflectionTestUtils.setField(promotion, "status", PromotionStatus.cancelled);
        assertThat(PromotionSummaryResponse.lifecycle(promotion, start.minusSeconds(1)))
                .isEqualTo(PromotionLifecycleStatus.cancelled);
    }

    @Test
    void returnsPromotionDetailWithProducts() {
        Promotion promotion = promotion(PromotionStatus.active);
        Product product = product(productId, ProductStatus.published);
        PromotionProduct association = PromotionProduct.builder()
                .promotionId(promotion.getId()).productId(productId).build();
        association.setTenantId(tenantId);
        when(promotionRepository.findByTenantIdAndId(tenantId, promotion.getId()))
                .thenReturn(Optional.of(promotion));
        when(promotionProductRepository.findByTenantIdAndPromotionId(tenantId, promotion.getId()))
                .thenReturn(List.of(association));
        when(productRepository.findByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of(product));

        var result = service.get(promotion.getId());

        assertThat(result.products()).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(productId));
    }

    @Test
    void cancelsPromotionWithActorSnapshot() {
        Promotion promotion = promotion(PromotionStatus.active);
        when(promotionRepository.findForUpdateByTenantIdAndId(tenantId, promotion.getId()))
                .thenReturn(Optional.of(promotion));
        when(promotionRepository.saveAndFlush(promotion)).thenReturn(promotion);
        when(promotionProductRepository.findByTenantIdAndPromotionId(tenantId, promotion.getId()))
                .thenReturn(List.of());
        when(productRepository.findByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of());

        var result = service.cancel(promotion.getId());

        assertThat(result.status()).isEqualTo(PromotionLifecycleStatus.cancelled);
        assertThat(result.cancelledByUserId()).isEqualTo(userId);
        assertThat(result.cancelledAt()).isNotNull();
    }

    @Test
    void cancellingAlreadyCancelledPromotionIsIdempotent() {
        Promotion promotion = promotion(PromotionStatus.cancelled);
        ReflectionTestUtils.setField(promotion, "cancelledByUserId", userId);
        ReflectionTestUtils.setField(promotion, "cancelledAt", startsAt);
        when(promotionRepository.findForUpdateByTenantIdAndId(tenantId, promotion.getId()))
                .thenReturn(Optional.of(promotion));
        when(promotionProductRepository.findByTenantIdAndPromotionId(tenantId, promotion.getId()))
                .thenReturn(List.of());
        when(productRepository.findByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of());

        var result = service.cancel(promotion.getId());

        assertThat(result.cancelledAt()).isEqualTo(startsAt);
        verify(promotionRepository, never()).saveAndFlush(any());
    }

    @Test
    void treatsCrossTenantPromotionAsNotFound() {
        UUID foreignPromotion = UUID.randomUUID();
        when(promotionRepository.findByTenantIdAndId(tenantId, foreignPromotion))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(foreignPromotion))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PROMOTION_NOT_FOUND"));
    }

    @Test
    void filtersPromotionsByTenantScopedProduct() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(productRepository.findByTenantIdAndId(tenantId, productId))
                .thenReturn(Optional.of(product(productId, ProductStatus.published)));
        when(promotionRepository.findByTenantIdAndProductId(tenantId, productId, pageable))
                .thenReturn(new PageImpl<>(List.of(promotion(PromotionStatus.active)), pageable, 1));

        assertThat(service.list(productId, pageable).items()).hasSize(1);
        verify(promotionRepository).findByTenantIdAndProductId(tenantId, productId, pageable);
    }

    @Test
    void updatesEditorFieldsAndEndsPromotionIdempotently() {
        Promotion promotion = promotion(PromotionStatus.active);
        Product product = product(productId, ProductStatus.published);
        when(promotionRepository.findForUpdateByTenantIdAndId(tenantId, promotion.getId()))
                .thenReturn(Optional.of(promotion));
        when(productRepository.findAllForUpdateByTenantIdAndIdIn(eq(tenantId), any(Collection.class)))
                .thenReturn(List.of(product));
        when(promotionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        UpdatePromotionRequest request = new UpdatePromotionRequest("Oferta web", "Descripcion",
                PromotionDiscountType.fixed_discount, new BigDecimal("5.00"), startsAt,
                startsAt.plusSeconds(3600), List.of(productId), List.of("ecommerce"), true, List.of());

        var updated = service.update(promotion.getId(), request);
        assertThat(updated.channels()).containsExactly("ecommerce");
        assertThat(updated.untilStockEnds()).isTrue();
        assertThat(updated.discountType()).isEqualTo(PromotionDiscountType.fixed_discount);

        when(promotionProductRepository.findByTenantIdAndPromotionId(tenantId, promotion.getId()))
                .thenReturn(List.of());
        when(productRepository.findByTenantIdAndIdIn(tenantId, List.of())).thenReturn(List.of());
        var ended = service.end(promotion.getId());
        assertThat(ended.status()).isEqualTo(PromotionLifecycleStatus.ended);
        service.end(promotion.getId());
    }

    private void assertInvalid(CreatePromotionRequest request) {
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PROMOTION_INVALID"));
    }

    private CreatePromotionRequest request(
            String name,
            PromotionDiscountType type,
            BigDecimal value,
            List<UUID> products,
            Instant endsAt) {
        return new CreatePromotionRequest(name, type, value, startsAt, endsAt, products);
    }

    private Product product(UUID id, ProductStatus status) {
        Product product = Product.builder()
                .sku("SKU-1")
                .name("Producto")
                .salePrice(new BigDecimal("100.00"))
                .status(status)
                .build();
        product.setTenantId(tenantId);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private Promotion promotion(PromotionStatus status) {
        Promotion promotion = Promotion.builder()
                .name("Oferta")
                .discountType(PromotionDiscountType.percentage)
                .discountValue(BigDecimal.TEN)
                .startsAt(Instant.parse("2020-01-01T00:00:00Z"))
                .status(status)
                .createdByUserId(userId)
                .build();
        promotion.setTenantId(tenantId);
        ReflectionTestUtils.setField(promotion, "id", UUID.randomUUID());
        return promotion;
    }
}
