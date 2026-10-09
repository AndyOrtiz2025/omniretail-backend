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
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbc;

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
    void exposesStockAvailabilityFromTheEcommerceBranch() throws Exception {
        StorefrontFixture fixture = persistStorefront();
        UUID branchId = persistEcommerceBranch(fixture);
        Product inStock = persistProduct(fixture, true);
        Product reserved = persistProduct(fixture, true);
        Product untracked = persistProduct(fixture, true);
        untracked.setTrackingStock(false);
        productRepository.save(untracked);
        persistBalance(fixture, branchId, inStock, "100.000", "25.000");
        persistBalance(fixture, branchId, reserved, "4.000", "4.000");

        String url = productsUrl(fixture.tenant().getSlug());
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].inStock", inStock.getId()).value(true))
                .andExpect(jsonPath("$[?(@.id == '%s')].availableQuantity", inStock.getId()).value(75))
                .andExpect(jsonPath("$[?(@.id == '%s')].inStock", reserved.getId()).value(false))
                .andExpect(jsonPath("$[?(@.id == '%s')].availableQuantity", reserved.getId()).value(0))
                .andExpect(jsonPath("$[?(@.id == '%s')].inStock", untracked.getId()).value(true));

        mockMvc.perform(get(url + "/" + inStock.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.availableQuantity").value(75));

        mockMvc.perform(get(url + "/" + untracked.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.availableQuantity").doesNotExist());
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

    private UUID persistEcommerceBranch(StorefrontFixture fixture) {
        UUID branchId = UUID.randomUUID();
        UUID tenantId = fixture.tenant().getId();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """, branchId, tenantId, "BR-" + branchId.toString().substring(0, 8), "Sucursal " + branchId);
        jdbc.update("""
                INSERT INTO ecommerce_configs (tenant_id, enabled, store_name, default_branch_id)
                VALUES (?, TRUE, 'Tienda', ?)
                """, tenantId, branchId);
        return branchId;
    }

    private void persistBalance(
            StorefrontFixture fixture, UUID branchId, Product product, String quantity, String reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances (tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, NULL, ?, ?)
                """, fixture.tenant().getId(), branchId, product.getId(),
                new BigDecimal(quantity), new BigDecimal(reserved));
    }

    private static String productsUrl(String slug) {
        return BASE_URL + slug + "/products";
    }

    private record StorefrontFixture(Tenant tenant, Category category, Unit unit) {}
}
