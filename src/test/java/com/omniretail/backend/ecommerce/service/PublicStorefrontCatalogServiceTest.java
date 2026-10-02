package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.dto.ResolvedProductPrice;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import com.omniretail.backend.inventory.entity.InventoryBalance;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock private ProductPriceResolver productPriceResolver;
    @Mock private EcommerceConfigRepository ecommerceConfigRepository;
    @Mock private InventoryBalanceRepository inventoryBalanceRepository;

    @InjectMocks private PublicStorefrontCatalogService service;

    @BeforeEach
    void setUpPriceResolver() {
        lenient().when(productPriceResolver.resolveEffectivePrice(
                        any(), any(Product.class), any(Instant.class),
                        org.mockito.ArgumentMatchers.eq("ecommerce"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("75.00"),
                        new BigDecimal("75.00"),
                        BigDecimal.ZERO.setScale(2),
                        null));
    }

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
        when(unitRepository.findByTenantId(tenantId)).thenReturn(List.of(unit));
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
    void storefrontExposesBaseAndEffectivePromotionalPriceWithoutReplacingSalePrice() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID promotionId = UUID.randomUUID();
        Product product = product(productId, categoryId, unitId);
        Tenant tenant = tenant(tenantId);
        Category category = category(categoryId);
        Unit unit = unit(unitId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson"))
                .thenReturn(Optional.of(tenant));
        when(categoryRepository.findByTenantIdAndStatus(tenantId, CategoryStatus.active))
                .thenReturn(List.of(category));
        when(unitRepository.findByTenantId(tenantId)).thenReturn(List.of(unit));
        when(productRepository.findByTenantIdAndStatusAndChannelEcommerceTrue(
                tenantId, ProductStatus.published)).thenReturn(List.of(product));
        when(productPriceResolver.resolveEffectivePrice(
                org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(product),
                any(Instant.class), org.mockito.ArgumentMatchers.eq("ecommerce"),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ResolvedProductPrice(
                        new BigDecimal("75.00"), new BigDecimal("60.00"),
                        new BigDecimal("15.00"), promotionId));

        var result = service.listProducts("ferreteria-los-simpson");

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.salePrice()).isEqualByComparingTo("75.00");
            assertThat(item.basePrice()).isEqualByComparingTo("75.00");
            assertThat(item.effectivePrice()).isEqualByComparingTo("60.00");
            assertThat(item.discountAmount()).isEqualByComparingTo("15.00");
            assertThat(item.promotionId()).isEqualTo(promotionId);
        });
    }

    @Test
    void keepsAProductWhenItsCategoryIsNotPublic() {
        UUID tenantId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        Product product = product(UUID.randomUUID(), categoryId, unitId);
        Tenant tenant = tenant(tenantId);
        Unit unit = unit(unitId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson")).thenReturn(Optional.of(tenant));
        when(categoryRepository.findByTenantIdAndStatus(tenantId, CategoryStatus.active)).thenReturn(List.of());
        when(unitRepository.findByTenantId(tenantId)).thenReturn(List.of(unit));
        when(productRepository.findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published))
                .thenReturn(List.of(product));

        assertThat(service.listProducts("ferreteria-los-simpson"))
                .singleElement()
                .satisfies(item -> assertThat(item.categoryName()).isNull());
    }

    @Test
    void exposesAvailableStockFromTheEcommerceBranchForTrackedProducts() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        Product withStock = trackedProduct(UUID.randomUUID(), unitId);
        Product withoutBalance = trackedProduct(UUID.randomUUID(), unitId);
        Product untracked = product(UUID.randomUUID(), UUID.randomUUID(), unitId);
        Tenant tenant = tenant(tenantId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson")).thenReturn(Optional.of(tenant));
        when(productRepository.findByTenantIdAndStatusAndChannelEcommerceTrue(tenantId, ProductStatus.published))
                .thenReturn(List.of(withStock, withoutBalance, untracked));
        ecommerceBranch(tenantId, branchId);
        InventoryBalance stockBalance = balance(withStock.getId(), "100.000", "30.000");
        when(inventoryBalanceRepository.findByTenantIdAndBranchIdAndLocationIdIsNull(tenantId, branchId))
                .thenReturn(List.of(stockBalance));

        var response = service.listProducts("ferreteria-los-simpson");

        assertThat(response).hasSize(3);
        assertThat(response.get(0).inStock()).isTrue();
        assertThat(response.get(0).availableQuantity()).isEqualByComparingTo("70.000");
        assertThat(response.get(1).inStock()).isFalse();
        assertThat(response.get(1).availableQuantity()).isEqualByComparingTo("0");
        assertThat(response.get(2).inStock()).isTrue();
        assertThat(response.get(2).availableQuantity()).isNull();
    }

    @Test
    void marksAFullyReservedProductAsOutOfStock() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        Product product = trackedProduct(UUID.randomUUID(), UUID.randomUUID());
        Tenant tenant = tenant(tenantId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson")).thenReturn(Optional.of(tenant));
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, product.getId(), ProductStatus.published)).thenReturn(Optional.of(product));
        ecommerceBranch(tenantId, branchId);
        InventoryBalance reservedBalance = balance(product.getId(), "5.000", "5.000");
        when(inventoryBalanceRepository.findByTenantIdAndBranchIdAndLocationIdIsNull(tenantId, branchId))
                .thenReturn(List.of(reservedBalance));

        var response = service.getProduct("ferreteria-los-simpson", product.getId());

        assertThat(response.inStock()).isFalse();
        assertThat(response.availableQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void treatsTrackedProductsAsOutOfStockWhenTheStorefrontHasNoEcommerceBranch() {
        UUID tenantId = UUID.randomUUID();
        Product product = trackedProduct(UUID.randomUUID(), UUID.randomUUID());
        Tenant tenant = tenant(tenantId);
        when(tenantRepository.findBySlug("ferreteria-los-simpson")).thenReturn(Optional.of(tenant));
        when(productRepository.findByTenantIdAndIdAndStatusAndChannelEcommerceTrue(
                tenantId, product.getId(), ProductStatus.published)).thenReturn(Optional.of(product));
        when(ecommerceConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        var response = service.getProduct("ferreteria-los-simpson", product.getId());

        assertThat(response.inStock()).isFalse();
        assertThat(response.availableQuantity()).isEqualByComparingTo("0");
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

    private void ecommerceBranch(UUID tenantId, UUID branchId) {
        EcommerceConfig config = mock(EcommerceConfig.class);
        when(config.getDefaultBranchId()).thenReturn(branchId);
        when(ecommerceConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
    }

    private InventoryBalance balance(UUID productId, String quantity, String reserved) {
        InventoryBalance balance = mock(InventoryBalance.class);
        when(balance.getProductId()).thenReturn(productId);
        when(balance.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(balance.getReservedQuantity()).thenReturn(new BigDecimal(reserved));
        return balance;
    }

    private Product trackedProduct(UUID productId, UUID unitId) {
        Product product = product(productId, UUID.randomUUID(), unitId);
        when(product.getTrackingStock()).thenReturn(true);
        return product;
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
}
