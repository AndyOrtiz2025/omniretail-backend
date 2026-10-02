package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.ProductSalesPriceTierRequest;
import com.omniretail.backend.catalog.dto.ReplaceProductSalesPriceTiersRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductSalesPriceTier;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductSalesPriceTierRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductSalesPriceTierServiceTest {
    @Mock ProductRepository products; @Mock ProductSalesPriceTierRepository tiers; @Mock CurrentUser currentUser;
    @InjectMocks ProductSalesPriceTierService service;
    UUID tenant = UUID.randomUUID(); UUID productId = UUID.randomUUID(); Product product;
    @BeforeEach void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), tenant,
                UserType.employee, null, null, UUID.randomUUID()));
        product = Product.builder().status(ProductStatus.published).build();
        lenient().when(products.findByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
        lenient().when(products.findForUpdateByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(product));
    }
    @Test void replacesWholeSetOrderedByMinimumQuantity() {
        when(tiers.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));
        var result = service.replace(productId, new ReplaceProductSalesPriceTiersRequest(List.of(
                new ProductSalesPriceTierRequest(10, new BigDecimal("8.50"), true),
                new ProductSalesPriceTierRequest(2, new BigDecimal("9.50"), true))));
        assertThat(result).extracting(value -> value.minQuantity()).containsExactly(2, 10);
        verify(tiers).deleteByTenantIdAndProductId(tenant, productId);
    }
    @Test void rejectsDuplicatesArchivedProductsAndCrossTenantIds() {
        assertThatThrownBy(() -> service.replace(productId, new ReplaceProductSalesPriceTiersRequest(List.of(
                new ProductSalesPriceTierRequest(2, BigDecimal.ONE, true),
                new ProductSalesPriceTierRequest(2, BigDecimal.TEN, true)))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_PRICE_TIER_CONFLICT"));
        product.setStatus(ProductStatus.archived);
        assertThatThrownBy(() -> service.replace(productId, new ReplaceProductSalesPriceTiersRequest(List.of())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_ARCHIVED"));
        assertThatThrownBy(() -> service.list(UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }
    @Test void rejectsQuantityPriceAndPrecisionOutsideContract() {
        assertInvalid(new ProductSalesPriceTierRequest(1, BigDecimal.ONE, true));
        assertInvalid(new ProductSalesPriceTierRequest(1_000_000, BigDecimal.ONE, true));
        assertInvalid(new ProductSalesPriceTierRequest(2, BigDecimal.ZERO, true));
        assertInvalid(new ProductSalesPriceTierRequest(2, new BigDecimal("1.001"), true));
        assertInvalid(new ProductSalesPriceTierRequest(2, new BigDecimal("10000000.00"), true));
    }
    private void assertInvalid(ProductSalesPriceTierRequest tier) {
        assertThatThrownBy(() -> service.replace(productId,
                new ReplaceProductSalesPriceTiersRequest(List.of(tier))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_PRICE_TIER_INVALID"));
    }
}
