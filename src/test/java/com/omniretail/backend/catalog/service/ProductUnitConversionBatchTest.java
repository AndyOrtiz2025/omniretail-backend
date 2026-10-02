package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductUnitConversionRequest;
import com.omniretail.backend.catalog.dto.ReplaceProductUnitConversionsRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
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
class ProductUnitConversionBatchTest {
    @Mock UnitConversionRepository conversions; @Mock UnitRepository units; @Mock ProductRepository products;
    @Mock BusinessConfigService businessConfig; @Mock CurrentUser currentUser;
    @InjectMocks UnitConversionService service;
    UUID tenant = UUID.randomUUID(); UUID productId = UUID.randomUUID();
    UUID base = UUID.randomUUID(); UUID sale = UUID.randomUUID();
    @BeforeEach void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), tenant,
                UserType.employee, null, null, UUID.randomUUID()));
        when(businessConfig.getConfig()).thenReturn(new BusinessConfigResponse(tenant, BusinessPreset.custom,
                true, false, false, false, false, true, true, false, true, List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(true, false, false, false)));
        when(products.findForUpdateByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(
                Product.builder().baseUnitId(base).saleUnitId(sale).build()));
        when(units.existsByIdAndTenantIdAndStatus(base, tenant, UnitStatus.active)).thenReturn(true);
        when(units.existsByIdAndTenantIdAndStatus(sale, tenant, UnitStatus.active)).thenReturn(true);
    }
    @Test void replacesAllProductConversionsInOneServiceTransaction() {
        when(conversions.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));
        service.replaceForProduct(productId, new ReplaceProductUnitConversionsRequest(List.of(
                new ProductUnitConversionRequest(sale, base, new BigDecimal("12.000000")))));
        verify(conversions).deleteByTenantIdAndProductId(tenant, productId);
        verify(conversions).flush(); verify(conversions).saveAllAndFlush(anyList());
    }
    @Test void rejectsDuplicatePairsAndUnitsOutsideProductConfiguration() {
        var pair = new ProductUnitConversionRequest(sale, base, BigDecimal.ONE);
        assertThatThrownBy(() -> service.replaceForProduct(productId,
                new ReplaceProductUnitConversionsRequest(List.of(pair, pair))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("UNIT_CONVERSION_CONFLICT"));
        UUID other = UUID.randomUUID();
        when(units.existsByIdAndTenantIdAndStatus(other, tenant, UnitStatus.active)).thenReturn(true);
        assertThatThrownBy(() -> service.replaceForProduct(productId,
                new ReplaceProductUnitConversionsRequest(List.of(
                        new ProductUnitConversionRequest(other, base, BigDecimal.ONE)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("UNIT_CONVERSION_PRODUCT_UNIT_MISMATCH"));
    }
}
