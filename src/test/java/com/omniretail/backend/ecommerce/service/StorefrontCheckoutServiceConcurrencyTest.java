package com.omniretail.backend.ecommerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.SubscriptionTestFixtures;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutItemRequest;
import com.omniretail.backend.ecommerce.dto.StorefrontCheckoutRequest;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StorefrontCheckoutServiceConcurrencyTest {

    @Autowired private SaasPlanRepository saasPlans;
    @Autowired private TenantSubscriptionRepository tenantSubscriptions;
    @Autowired private StorefrontCheckoutService checkoutService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentCheckoutsForTheLastUnitAllowExactlyOneReservation() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> checkout(fixture, "checkout-a", ready, start));
            Future<Outcome> second = executor.submit(() -> checkout(fixture, "checkout-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
            assertThat(outcomes).filteredOn(value -> !value.succeeded()).singleElement()
                    .extracting(Outcome::errorCode).isEqualTo("INSUFFICIENT_STOCK");
            BigDecimal reserved = jdbcTemplate.queryForObject(
                    "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                    BigDecimal.class, fixture.balanceId());
            assertThat(reserved).isEqualByComparingTo("1.000");
            Long reservations = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM inventory_reservations WHERE tenant_id = ?",
                    Long.class, fixture.tenantId());
            assertThat(reservations).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome checkout(Fixture fixture, String key, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Los checkouts no iniciaron.");
        try {
            checkoutService.checkout(fixture.slug(), key, request(fixture.productId()));
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        String slug = "checkout-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tienda " + suffix, slug);
        SubscriptionTestFixtures.provisionBasic(tenantSubscriptions, saasPlans, tenantId,
                List.of("ecommerce_delivery"));
        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbcTemplate.update(
                "INSERT INTO ecommerce_configs (tenant_id, enabled, store_name, require_account_for_checkout, guest_tracking_enabled, allowed_delivery_methods, allowed_payment_methods, default_branch_id) VALUES (?, true, ?, false, true, ARRAY['home_delivery'], ARRAY['card'], ?)",
                tenantId, "Tienda " + suffix, branchId);
        jdbcTemplate.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoría " + suffix, "categoria-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, 'UND', 'Unidad', 'und', 'unit', false, 'active')",
                unitId, tenantId);
        jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, sku, name, product_type, category_id, base_unit_id, sale_price, status, tracking_stock, channel_ecommerce) VALUES (?, ?, ?, ?, 'physical', ?, ?, 10.00, 'published', true, true)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        jdbcTemplate.update(
                "INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, 1.000, 0.000)",
                balanceId, tenantId, branchId, productId);
        return new Fixture(tenantId, branchId, productId, balanceId, slug);
    }

    private static StorefrontCheckoutRequest request(UUID productId) {
        return new StorefrontCheckoutRequest(
                List.of(new StorefrontCheckoutItemRequest(productId, BigDecimal.ONE)),
                "María López", "maria@example.com", "55551234", "7a Avenida #1",
                "", "Guatemala", "", "", "María López", "4242");
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID balanceId, String slug) {}
    private record Outcome(boolean succeeded, String errorCode) {}
}
