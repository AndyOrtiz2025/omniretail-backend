package com.omniretail.backend.catalog.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.catalog.repository.PromotionRepository;
import com.omniretail.backend.catalog.service.ProductPriceResolver;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class CatalogPromotionsJpaTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private ProductPriceResolver productPriceResolver;

    @Test
    void applicableQueryIgnoresScheduledExpiredAndCancelledPromotions() {
        Fixture fixture = fixture();
        Instant at = Instant.parse("2026-09-29T12:00:00Z");
        UUID applicable = insertPromotion(
                fixture, "Aplicable", "percentage", "10.00",
                at.minusSeconds(60), at.plusSeconds(60), "active", false);
        insertPromotion(fixture, "Programada", "percentage", "20.00",
                at.plusSeconds(1), null, "active", false);
        insertPromotion(fixture, "Expirada", "percentage", "30.00",
                at.minusSeconds(120), at, "active", false);
        insertPromotion(fixture, "Cancelada", "fixed_price", "1.00",
                at.minusSeconds(120), null, "cancelled", true);

        var result = promotionRepository.findApplicable(
                fixture.tenant(), fixture.product(), PromotionStatus.active, at);

        assertThat(result).extracting(Promotion::getId).containsExactly(applicable);
    }

    @Test
    void resolvingPromotionDoesNotChangeProductOrGeneratePriceHistory() {
        Fixture fixture = fixture();
        Instant at = Instant.parse("2026-09-29T12:00:00Z");
        insertPromotion(fixture, "Oferta", "percentage", "25.00",
                at.minusSeconds(60), null, "active", false);

        var result = productPriceResolver.resolveEffectivePrice(
                fixture.tenant(), fixture.product(), at);

        assertThat(result.basePrice()).isEqualByComparingTo("100.00");
        assertThat(result.effectivePrice()).isEqualByComparingTo("75.00");
        assertThat(jdbc.queryForObject(
                "SELECT sale_price FROM products WHERE id = ?",
                java.math.BigDecimal.class,
                fixture.product())).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM product_price_history WHERE product_id = ?",
                Long.class,
                fixture.product())).isZero();
    }

    @Test
    void promotionProductRejectsDuplicates() {
        Fixture fixture = fixture();
        UUID promotion = insertPromotion(
                fixture, "Oferta", "percentage", "10.00",
                Instant.now().minusSeconds(60), null, "active", false);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promotion_products (tenant_id, promotion_id, product_id)
                VALUES (?, ?, ?)
                """, fixture.tenant(), promotion, fixture.product()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void promotionProductRejectsCrossTenantAssociations() {
        Fixture promotionOwner = fixture();
        Fixture productOwner = fixture();
        UUID promotion = insertPromotion(
                promotionOwner, "Oferta", "percentage", "10.00",
                Instant.now().minusSeconds(60), null, "active", false);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promotion_products (tenant_id, promotion_id, product_id)
                VALUES (?, ?, ?)
                """, promotionOwner.tenant(), promotion, productOwner.product()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activePromotionRejectsCancellationMetadata() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promotions
                    (tenant_id, name, discount_type, discount_value, starts_at, status,
                     created_by_user_id, cancelled_by_user_id, cancelled_at)
                VALUES (?, 'Invalida', 'percentage', 10.00, now(), 'active', ?, ?, now())
                """, fixture.tenant(), fixture.user(), fixture.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancelledPromotionRequiresBothCancellationFields() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promotions
                    (tenant_id, name, discount_type, discount_value, starts_at, status,
                     created_by_user_id, cancelled_by_user_id, cancelled_at)
                VALUES (?, 'Invalida', 'percentage', 10.00, now(), 'cancelled', ?, NULL, now())
                """, fixture.tenant(), fixture.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInvalidDiscountAndDateRules() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promotions
                    (tenant_id, name, discount_type, discount_value, starts_at, ends_at,
                     status, created_by_user_id)
                VALUES (?, 'Invalida', 'percentage', 0.00, now(), now(), 'active', ?)
                """, fixture.tenant(), fixture.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationInstallsTenantAndHistoricalSnapshotForeignKeys() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE constraint_type = 'FOREIGN KEY'
                  AND constraint_name IN (
                    'fk_promotions_tenant',
                    'fk_promotion_products_tenant',
                    'fk_promotion_products_promotion',
                    'fk_promotion_products_product',
                    'fk_sale_items_promotion',
                    'fk_order_items_promotion')
                """, Long.class)).isEqualTo(6L);
        assertThat(jdbc.queryForList("""
                SELECT delete_rule
                FROM information_schema.referential_constraints
                WHERE constraint_name IN ('fk_sale_items_promotion', 'fk_order_items_promotion')
                """, String.class)).containsOnly("RESTRICT");
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Promotions JPA', ?)",
                tenant, "promotions-jpa-" + tenant);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status)
                VALUES (?, ?, 'Gestor', ?, 'employee', 'active')
                """, user, tenant, "promotions-" + user + "@example.com");
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     sale_price, status, tracking_stock)
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, 100.00, 'published', true)
                """, product, tenant, "SKU-" + product, category, unit);
        return new Fixture(tenant, user, product);
    }

    private UUID insertPromotion(
            Fixture fixture,
            String name,
            String type,
            String value,
            Instant startsAt,
            Instant endsAt,
            String status,
            boolean cancelled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO promotions
                    (id, tenant_id, name, discount_type, discount_value, starts_at, ends_at,
                     status, created_by_user_id, cancelled_by_user_id, cancelled_at)
                VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, ?, ?, ?, ?, ?)
                """,
                id, fixture.tenant(), name, type, value,
                jdbcTimestamp(startsAt), jdbcTimestamp(endsAt), status,
                fixture.user(), cancelled ? fixture.user() : null,
                cancelled ? jdbcTimestamp(startsAt.plusSeconds(1)) : null);
        jdbc.update("""
                INSERT INTO promotion_products (tenant_id, promotion_id, product_id)
                VALUES (?, ?, ?)
                """, fixture.tenant(), id, fixture.product());
        return id;
    }

    private static OffsetDateTime jdbcTimestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record Fixture(UUID tenant, UUID user, UUID product) {}
}
