package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicStorefrontCatalogServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UnitRepository unitRepository;

    @InjectMocks private PublicStorefrontCatalogService service;

    @Test
    void listsOnlyPublishedEcommerceProductsFromTheActiveStorefront() {
        UUID tenantId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        Product product = product(UUID.randomUUID(), categoryId, unitId);
        Tenant tenant = tenant(tenantId);
        Category category = category(categoryId);
        Unit unit = unit(unitId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson")).thenReturn(Optional.of(tenant));
        when(categoryRepository.findByTenantIdAndStatus(tenantId, CategoryStatus.active))
                .thenReturn(List.of(category));
        when(unitRepository.findByTenantIdAndStatus(tenantId, UnitStatus.active)).thenReturn(List.of(unit));
        when(productRepository.findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published))
                .thenReturn(List.of(product));

        var response = service.listProducts("ferreteria-los-simpson");

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("HER-001");
            assertThat(item.categoryName()).isEqualTo("Herramientas");
            assertThat(item.saleUnitName()).isEqualTo("Unidad");
        });
    }

    @Test
    void hidesAProductWhenItsCategoryIsNotPublic() {
        UUID tenantId = UUID.randomUUID();
        Product product = productWithCategory(UUID.randomUUID());
        Tenant tenant = tenant(tenantId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson")).thenReturn(Optional.of(tenant));
        when(categoryRepository.findByTenantIdAndStatus(tenantId, CategoryStatus.active)).thenReturn(List.of());
        when(unitRepository.findByTenantIdAndStatus(tenantId, UnitStatus.active)).thenReturn(List.of());
        when(productRepository.findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published))
                .thenReturn(List.of(product));

        assertThat(service.listProducts("ferreteria-los-simpson")).isEmpty();
    }

    @Test
    void rejectsAnUnknownOrInactiveStorefront() {
        when(tenantRepository.findBySlug("cerrada")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listProducts("cerrada"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STOREFRONT_NOT_FOUND"));
    }

    private Tenant tenant(UUID tenantId) {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(tenant.getStatus()).thenReturn(TenantStatus.active);
        return tenant;
    }

    private Category category(UUID categoryId) {
        Category category = mock(Category.class);
        when(category.getId()).thenReturn(categoryId);
        when(category.getName()).thenReturn("Herramientas");
        return category;
    }

    private Unit unit(UUID unitId) {
        Unit unit = mock(Unit.class);
        when(unit.getId()).thenReturn(unitId);
        when(unit.getName()).thenReturn("Unidad");
        return unit;
    }

    private Product product(UUID productId, UUID categoryId, UUID unitId) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getSku()).thenReturn("HER-001");
        when(product.getName()).thenReturn("Martillo de uña");
        when(product.getDescription()).thenReturn("Uso general");
        when(product.getBrand()).thenReturn("Truper");
        when(product.getSalePrice()).thenReturn(new BigDecimal("75.00"));
        when(product.getCategoryId()).thenReturn(categoryId);
        when(product.getBaseUnitId()).thenReturn(unitId);
        return product;
    }

    private Product productWithCategory(UUID categoryId) {
        Product product = mock(Product.class);
        when(product.getCategoryId()).thenReturn(categoryId);
        return product;
    }
}
