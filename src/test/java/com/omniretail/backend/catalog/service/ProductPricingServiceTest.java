package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.UpdateProductPriceRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductPriceHistory;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductPriceHistoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ProductPricingServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock private ProductRepository productRepository;
    @Mock private ProductPriceHistoryRepository historyRepository;
    @Mock private CurrentUser currentUser;

    private ProductPricingService service;

    @BeforeEach
    void setUp() {
        service = new ProductPricingService(productRepository, historyRepository, currentUser);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                USER, TENANT, UserType.employee, null, null, UUID.randomUUID()));
    }

    @Test
    void updateLocksTenantProductAndCreatesNormalizedHistory() {
        Product product = product(new BigDecimal("10.00"), ProductStatus.published);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product));
        given(productRepository.saveAndFlush(product)).willReturn(product);

        var response = service.updatePrice(
                PRODUCT, new UpdateProductPriceRequest(new BigDecimal("12.50"), "  Ajuste anual  "));

        assertThat(response.salePrice()).isEqualByComparingTo("12.50");
        ArgumentCaptor<ProductPriceHistory> history =
                ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(history.getValue().getProductId()).isEqualTo(PRODUCT);
        assertThat(history.getValue().getOldPrice()).isEqualByComparingTo("10.00");
        assertThat(history.getValue().getNewPrice()).isEqualByComparingTo("12.50");
        assertThat(history.getValue().getChangedByUserId()).isEqualTo(USER);
        assertThat(history.getValue().getReason()).isEqualTo("Ajuste anual");
    }

    @Test
    void equalPriceIsNoOpWithoutHistoryOrSave() {
        Product product = product(new BigDecimal("10.00"), ProductStatus.published);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product));

        service.updatePrice(PRODUCT, new UpdateProductPriceRequest(new BigDecimal("10.0"), "ignored"));

        verify(historyRepository, never()).save(any());
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void archivedAndCrossTenantProductsAreRejected() {
        Product archived = product(BigDecimal.ONE, ProductStatus.archived);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(archived));
        assertCode(
                () -> service.updatePrice(PRODUCT, new UpdateProductPriceRequest(BigDecimal.TEN, null)),
                "PRODUCT_ARCHIVED");

        UUID foreign = UUID.randomUUID();
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, foreign))
                .willReturn(Optional.empty());
        assertCode(
                () -> service.updatePrice(foreign, new UpdateProductPriceRequest(BigDecimal.TEN, null)),
                "PRODUCT_NOT_FOUND");
    }

    @Test
    void normalizedReasonLengthIsEnforced() {
        Product product = product(BigDecimal.ONE, ProductStatus.published);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product));

        assertCode(
                () -> service.updatePrice(
                        PRODUCT, new UpdateProductPriceRequest(BigDecimal.TEN, "x".repeat(201))),
                "PRODUCT_PRICE_REASON_INVALID");
    }

    @Test
    void priceUsesProductPrecisionAndRange() {
        Product product = product(BigDecimal.ONE, ProductStatus.published);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product));

        assertCode(
                () -> service.updatePrice(
                        PRODUCT, new UpdateProductPriceRequest(new BigDecimal("1.001"), null)),
                "PRODUCT_PRICE_INVALID");
        assertCode(
                () -> service.updatePrice(
                        PRODUCT, new UpdateProductPriceRequest(new BigDecimal("10000000.00"), null)),
                "PRODUCT_PRICE_INVALID");
        verify(historyRepository, never()).save(any());
    }

    @Test
    void reasonLimitIsMeasuredAfterTrim() {
        Product product = product(BigDecimal.ONE, ProductStatus.published);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product));
        given(productRepository.saveAndFlush(product)).willReturn(product);

        service.updatePrice(
                PRODUCT,
                new UpdateProductPriceRequest(BigDecimal.TEN, "  " + "x".repeat(200) + "  "));

        ArgumentCaptor<ProductPriceHistory> history = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getReason()).hasSize(200);
    }

    @Test
    void blankReasonBecomesNull() {
        Product product = product(BigDecimal.ONE, ProductStatus.published);
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product));
        given(productRepository.saveAndFlush(product)).willReturn(product);

        service.updatePrice(PRODUCT, new UpdateProductPriceRequest(BigDecimal.TEN, "   "));

        ArgumentCaptor<ProductPriceHistory> history = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getReason()).isNull();
    }

    @Test
    void historyIsTenantScopedAndKeepsRepositoryNewestFirstContract() {
        Product product = product(BigDecimal.ONE, ProductStatus.published);
        PageRequest pageable = PageRequest.of(1, 5);
        ProductPriceHistory history = ProductPriceHistory.builder()
                .tenantId(TENANT)
                .productId(PRODUCT)
                .newPrice(BigDecimal.ONE)
                .changedByUserId(USER)
                .build();
        given(productRepository.findByTenantIdAndId(TENANT, PRODUCT)).willReturn(Optional.of(product));
        given(historyRepository.findByTenantIdAndProductIdOrderByCreatedAtDesc(TENANT, PRODUCT, pageable))
                .willReturn(new PageImpl<>(List.of(history), pageable, 6));

        var response = service.history(PRODUCT, pageable);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.totalItems()).isEqualTo(6);
        verify(historyRepository)
                .findByTenantIdAndProductIdOrderByCreatedAtDesc(TENANT, PRODUCT, pageable);
    }

    private static Product product(BigDecimal price, ProductStatus status) {
        Product product = Product.builder()
                .sku("SKU")
                .name("Producto")
                .salePrice(price)
                .status(status)
                .build();
        product.setTenantId(TENANT);
        ReflectionTestUtils.setField(product, "id", PRODUCT);
        return product;
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
