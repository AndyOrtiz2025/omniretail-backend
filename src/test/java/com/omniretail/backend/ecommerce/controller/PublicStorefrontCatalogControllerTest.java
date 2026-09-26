package com.omniretail.backend.ecommerce.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitCategory;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicStorefrontCatalogControllerTest {

    private static final String BASE_URL = "/api/v1/public/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void anonymousRequestCanListAndReadAProduct() throws Exception {
        StorefrontFixture fixture = persistStorefront();
        Product product = persistProduct(fixture, true);

        mockMvc.perform(get(productsUrl(fixture.tenant().getSlug())))
                .andExpect(status().isOk());

        mockMvc.perform(get(productsUrl(fixture.tenant().getSlug()) + "/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.sku").value(product.getSku()));
    }

    @Test
    void unknownSlugReturnsStorefrontNotFound() throws Exception {
        mockMvc.perform(get(productsUrl("tienda-inexistente-" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOREFRONT_NOT_FOUND"));
    }

    @Test
    void productFromAnotherStorefrontReturnsNotFound() throws Exception {
        StorefrontFixture firstStorefront = persistStorefront();
        StorefrontFixture secondStorefront = persistStorefront();
        Product secondProduct = persistProduct(secondStorefront, true);

        mockMvc.perform(get(productsUrl(firstStorefront.tenant().getSlug()) + "/" + secondProduct.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void productWithoutEcommerceChannelReturnsNotFound() throws Exception {
        StorefrontFixture fixture = persistStorefront();
        Product product = persistProduct(fixture, false);

        mockMvc.perform(get(productsUrl(fixture.tenant().getSlug()) + "/" + product.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private StorefrontFixture persistStorefront() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Tienda " + suffix)
                .slug("tienda-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        Category category = Category.builder()
                .name("Herramientas " + suffix)
                .slug("herramientas-" + suffix)
                .status(CategoryStatus.active)
                .build();
        category.setTenantId(tenant.getId());
        category = categoryRepository.save(category);
        Unit unit = Unit.builder()
                .code("UND-" + suffix.substring(0, 8))
                .name("Unidad")
                .symbol("und")
                .category(UnitCategory.unit)
                .status(UnitStatus.active)
                .build();
        unit.setTenantId(tenant.getId());
        unit = unitRepository.save(unit);
        return new StorefrontFixture(tenant, category, unit);
    }

    private Product persistProduct(StorefrontFixture fixture, boolean ecommerceEnabled) {
        Product product = Product.builder()
                .sku("SKU-" + UUID.randomUUID())
                .name("Producto público")
                .categoryId(fixture.category().getId())
                .baseUnitId(fixture.unit().getId())
                .salePrice(new BigDecimal("75.00"))
                .status(ProductStatus.published)
                .channelEcommerce(ecommerceEnabled)
                .build();
        product.setTenantId(fixture.tenant().getId());
        return productRepository.save(product);
    }

    private static String productsUrl(String slug) {
        return BASE_URL + slug + "/products";
    }

    private record StorefrontFixture(Tenant tenant, Category category, Unit unit) {}
}
