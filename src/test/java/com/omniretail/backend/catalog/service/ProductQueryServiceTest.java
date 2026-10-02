package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.ProductChannel;
import com.omniretail.backend.catalog.dto.ProductPromotionFilter;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductQueryServiceTest {

    @Autowired private ProductService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;

    @Test
    void searchFiltersChannelsAndPrimaryImageAreAppliedBeforePagination() {
        Fixture fixture = fixture();
        UUID target = product(
                fixture, fixture.category(), "published", "physical", "SKU-DRILL-01",
                "Taladro profesional", "Bosch", "740000000001", true, false, false);
        UUID ecommerce = product(
                fixture, fixture.category(), "published", "physical", "SKU-WEB-01",
                "Producto web", "Makita", "740000000002", false, true, false);
        product(
                fixture, fixture.category(), "archived", "service", "SKU-SERVICE-01",
                "Instalacion", "Servicios", "740000000003", false, false, true);
        jdbc.update("""
                INSERT INTO product_media (tenant_id, product_id, type, url, is_primary, sort_order)
                VALUES (?, ?, 'image', '/media/products/drill.webp', true, 0)
                """, fixture.tenant(), target);

        for (String term : List.of("taladro", "sku-drill", "bosch", "740000000001")) {
            var result = service.list(
                    term, null, null, null, List.of(), ProductPromotionFilter.all, PageRequest.of(0, 20));
            assertThat(result.items()).extracting(item -> item.id()).containsExactly(target);
            assertThat(result.items().getFirst().primaryImageUrl())
                    .isEqualTo("/media/products/drill.webp");
        }

        var filtered = service.list(
                null,
                ProductStatus.published,
                ProductType.physical,
                fixture.category(),
                List.of(ProductChannel.pos, ProductChannel.ecommerce),
                ProductPromotionFilter.all,
                PageRequest.of(0, 1));
        assertThat(filtered.totalItems()).isEqualTo(2);
        assertThat(filtered.totalPages()).isEqualTo(2);
        assertThat(filtered.items()).hasSize(1);

        var withoutImage = service.list(
                "SKU-WEB", null, null, null, List.of(), ProductPromotionFilter.all, PageRequest.of(0, 20));
        assertThat(withoutImage.items()).extracting(item -> item.id()).containsExactly(ecommerce);
        assertThat(withoutImage.items().getFirst().primaryImageUrl()).isNull();
    }

    @Test
    void promotionWithUsesOnlyCurrentlyActiveTenantScopedAssociations() {
        Fixture fixture = fixture();
        UUID active = product(fixture, fixture.category(), "published", "physical", "ACTIVE",
                "Promocion activa", null, null, true, true, false);
        UUID future = product(fixture, fixture.category(), "published", "physical", "FUTURE",
                "Promocion futura", null, null, true, true, false);
        UUID expired = product(fixture, fixture.category(), "published", "physical", "EXPIRED",
                "Promocion vencida", null, null, true, true, false);
        UUID none = product(fixture, fixture.category(), "published", "physical", "NONE",
                "Sin promocion", null, null, true, true, false);
        Instant now = Instant.now();
        promotion(fixture, active, now.minusSeconds(3600), null);
        promotion(fixture, future, now.plusSeconds(3600), null);
        promotion(fixture, expired, now.minusSeconds(7200), now.minusSeconds(3600));

        var with = service.list(
                null, null, null, null, List.of(), ProductPromotionFilter.with, PageRequest.of(0, 20));
        assertThat(with.items()).extracting(item -> item.id()).containsExactly(active);

        var without = service.list(
                null, null, null, null, List.of(), ProductPromotionFilter.without, PageRequest.of(0, 20));
        assertThat(without.items()).extracting(item -> item.id())
                .containsExactlyInAnyOrder(future, expired, none)
                .doesNotContain(active);

        Fixture otherTenant = fixture();
        UUID otherProduct = product(otherTenant, otherTenant.category(), "published", "physical", "OTHER",
                "Otro tenant", null, null, true, true, false);
        promotion(otherTenant, otherProduct, now.minusSeconds(3600), null);
        useActor(fixture);
        assertThat(service.list(
                        null, null, null, null, List.of(), ProductPromotionFilter.with,
                        PageRequest.of(0, 20)).items())
                .extracting(item -> item.id())
                .containsExactly(active)
                .doesNotContain(otherProduct);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Product query', ?)",
                tenant, "product-query-" + tenant);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Herramientas', ?)",
                category, tenant, "tools-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status)
                VALUES (?, ?, 'Catalog actor', ?, 'employee', 'active')
                """, user, tenant, "catalog-" + user + "@test.local");
        Fixture fixture = new Fixture(tenant, category, unit, user);
        useActor(fixture);
        return fixture;
    }

    private UUID product(
            Fixture fixture,
            UUID category,
            String status,
            String type,
            String sku,
            String name,
            String brand,
            String barcode,
            boolean pos,
            boolean ecommerce,
            boolean mobileApp) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, barcode, name, brand, product_type, category_id,
                     base_unit_id, status, channel_pos, channel_ecommerce, channel_mobile_app)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, fixture.tenant(), sku + "-" + id.toString().substring(0, 8), barcode, name, brand, type,
                category, fixture.unit(), status, pos, ecommerce, mobileApp);
        return id;
    }

    private void promotion(Fixture fixture, UUID product, Instant startsAt, Instant endsAt) {
        UUID promotion = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO promotions
                    (id, tenant_id, name, discount_type, discount_value, starts_at, ends_at,
                     status, created_by_user_id)
                VALUES (?, ?, ?, 'percentage', 10, ?, ?, 'active', ?)
                """, promotion, fixture.tenant(), "Promo " + promotion,
                Timestamp.from(startsAt), endsAt == null ? null : Timestamp.from(endsAt), fixture.user());
        jdbc.update("""
                INSERT INTO promotion_products (tenant_id, promotion_id, product_id)
                VALUES (?, ?, ?)
                """, fixture.tenant(), promotion, product);
    }

    private void useActor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee, null, null, UUID.randomUUID()));
    }

    private record Fixture(UUID tenant, UUID category, UUID unit, UUID user) {}
}
