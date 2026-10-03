package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.UnitConversion;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductUnitConversionResolverTest {

    @Mock private UnitConversionRepository conversions;
    @InjectMocks private ProductUnitConversionResolver resolver;

    @Test
    void convertsCommercialQuantityUsingProductSpecificFactor() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID saleUnitId = UUID.randomUUID();
        UUID baseUnitId = UUID.randomUUID();
        Product product = Product.builder()
                .baseUnitId(baseUnitId).saleUnitId(saleUnitId).trackingStock(true).build();
        ReflectionTestUtils.setField(product, "id", productId);
        when(conversions.findByTenantIdAndProductIdAndFromUnitIdAndToUnitId(
                tenantId, productId, saleUnitId, baseUnitId))
                .thenReturn(Optional.of(UnitConversion.builder().factor(new BigDecimal("12.000000")).build()));

        BigDecimal result = resolver.toBaseQuantity(tenantId, product, new BigDecimal("2"));

        assertThat(result).isEqualByComparingTo("24.000");
    }

    @Test
    void fallsBackToGlobalConversion() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID saleUnitId = UUID.randomUUID();
        UUID baseUnitId = UUID.randomUUID();
        Product product = Product.builder()
                .baseUnitId(baseUnitId).saleUnitId(saleUnitId).trackingStock(true).build();
        ReflectionTestUtils.setField(product, "id", productId);
        when(conversions.findByTenantIdAndProductIdAndFromUnitIdAndToUnitId(
                tenantId, productId, saleUnitId, baseUnitId)).thenReturn(Optional.empty());
        when(conversions.findByTenantIdAndFromUnitIdAndToUnitIdAndProductIdIsNull(
                tenantId, saleUnitId, baseUnitId))
                .thenReturn(Optional.of(UnitConversion.builder().factor(new BigDecimal("6.000000")).build()));

        assertThat(resolver.toBaseQuantity(tenantId, product, new BigDecimal("2")))
                .isEqualByComparingTo("12.000");
    }

    @Test
    void rejectsMissingRequiredConversion() {
        UUID tenantId = UUID.randomUUID();
        UUID saleUnitId = UUID.randomUUID();
        UUID baseUnitId = UUID.randomUUID();
        Product product = Product.builder()
                .baseUnitId(baseUnitId).saleUnitId(saleUnitId).trackingStock(true).build();
        ReflectionTestUtils.setField(product, "id", UUID.randomUUID());

        assertThatThrownBy(() -> resolver.toBaseQuantity(tenantId, product, BigDecimal.ONE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("UNIT_CONVERSION_REQUIRED"));
    }

    @Test
    void rejectsFractionalPhysicalStockQuantity() {
        UUID unitId = UUID.randomUUID();
        Product product = Product.builder()
                .baseUnitId(unitId).saleUnitId(unitId).trackingStock(true).build();

        assertThatThrownBy(() -> resolver.toBaseQuantity(
                        UUID.randomUUID(), product, new BigDecimal("1.500")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVALID_INVENTORY_QUANTITY"));
    }
}
