package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductKitComponentRequest;
import com.omniretail.backend.catalog.dto.ReplaceProductKitComponentsRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductKitComponentRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductKitServiceTest {
    @Mock ProductRepository products; @Mock ProductKitComponentRepository components;
    @Mock BusinessConfigService businessConfig; @Mock TenantCapabilityGuard capabilities;
    @Mock CurrentUser currentUser; @InjectMocks ProductKitService service;
    UUID tenant = UUID.randomUUID(); UUID kitId = UUID.randomUUID(); UUID componentId = UUID.randomUUID();
    Product kit; Product component;
    @BeforeEach void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), tenant,
                UserType.employee, null, null, UUID.randomUUID()));
        when(businessConfig.getConfig()).thenReturn(new BusinessConfigResponse(tenant, BusinessPreset.custom,
                true, false, false, false, false, true, true, true, true, List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(true, false, false, false)));
        kit = product(kitId, ProductType.kit, ProductStatus.archived, false);
        component = product(componentId, ProductType.physical, ProductStatus.published, true);
        lenient().when(products.findByTenantIdAndId(tenant, kitId)).thenReturn(Optional.of(kit));
        lenient().when(products.findForUpdateByTenantIdAndId(tenant, kitId)).thenReturn(Optional.of(kit));
    }
    @Test void replacesComponentsAndRequiresBothKitCapabilities() {
        when(products.findAllForUpdateByTenantIdAndIdIn(eq(tenant),
                argThat(ids -> ids.size() == 1 && ids.contains(componentId))))
                .thenReturn(List.of(component));
        service.replace(kitId, new ReplaceProductKitComponentsRequest(List.of(
                new ProductKitComponentRequest(componentId, new BigDecimal("2.000")))));
        verify(capabilities).ensureTenantCapability(tenant, SaasCapability.catalogKits);
        verify(components).saveAllAndFlush(anyList());
    }
    @Test void rejectsSelfReferenceNestedKitAndUntrackedPhysicalComponent() {
        assertCode(() -> service.replace(kitId, new ReplaceProductKitComponentsRequest(List.of(
                new ProductKitComponentRequest(kitId, BigDecimal.ONE)))), "KIT_COMPONENT_INVALID");
        Product nested = product(componentId, ProductType.kit, ProductStatus.published, false);
        when(products.findAllForUpdateByTenantIdAndIdIn(eq(tenant),
                argThat(ids -> ids.size() == 1 && ids.contains(componentId))))
                .thenReturn(List.of(nested));
        assertCode(() -> service.replace(kitId, new ReplaceProductKitComponentsRequest(List.of(
                new ProductKitComponentRequest(componentId, BigDecimal.ONE)))), "KIT_COMPONENT_INVALID");
        component.setTrackingStock(false);
        when(products.findAllForUpdateByTenantIdAndIdIn(eq(tenant),
                argThat(ids -> ids.size() == 1 && ids.contains(componentId))))
                .thenReturn(List.of(component));
        assertCode(() -> service.replace(kitId, new ReplaceProductKitComponentsRequest(List.of(
                new ProductKitComponentRequest(componentId, BigDecimal.ONE)))), "KIT_COMPONENT_INVALID");
    }
    @Test void rejectsWhenBusinessKitCapabilityIsDisabled() {
        when(businessConfig.getConfig()).thenReturn(new BusinessConfigResponse(tenant, BusinessPreset.custom,
                true, false, false, false, false, true, true, false, true, List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(true, false, false, false)));
        assertCode(() -> service.list(kitId), "BUSINESS_CAPABILITY_DISABLED");
    }
    @Test void rejectsWhenSaasCatalogKitsCapabilityIsDisabled() {
        doThrow(new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,
                "CAPABILITY_REQUIRED", "Capacidad no disponible."))
                .when(capabilities).ensureTenantCapability(tenant, SaasCapability.catalogKits);
        assertCode(() -> service.list(kitId), "CAPABILITY_REQUIRED");
    }
    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
    private Product product(UUID id, ProductType type, ProductStatus status, boolean stock) {
        Product value = Product.builder().sku("SKU").name("Product").productType(type)
                .status(status).trackingStock(stock).build();
        value.setTenantId(tenant); ReflectionTestUtils.setField(value, "id", id); return value;
    }
}
