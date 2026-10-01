package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.UpdateProductPriceRequest;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductPricingServiceConcurrencyTest {

    @Autowired private ProductPricingService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee,
                null, null, UUID.randomUUID()));
    }

    @Test
    void concurrentPriceChangesAreSerializedIntoOneContinuousHistoryChain() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BigDecimal> first = executor.submit(() -> update("20.00", ready, start));
            Future<BigDecimal> second = executor.submit(() -> update("30.00", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT old_price, new_price
                    FROM product_price_history
                    WHERE tenant_id = ? AND product_id = ?
                    """, fixture.tenant(), fixture.product());
            assertThat(rows).hasSize(2);

            Map<String, Object> firstLink = rows.stream()
                    .filter(row -> decimal(row.get("old_price")).compareTo(new BigDecimal("10.00")) == 0)
                    .findFirst()
                    .orElseThrow();
            BigDecimal middle = decimal(firstLink.get("new_price"));
            Map<String, Object> secondLink = rows.stream()
                    .filter(row -> decimal(row.get("old_price")).compareTo(middle) == 0)
                    .findFirst()
                    .orElseThrow();
            BigDecimal finalPrice = jdbc.queryForObject(
                    "SELECT sale_price FROM products WHERE id = ?", BigDecimal.class, fixture.product());
            assertThat(finalPrice).isEqualByComparingTo(decimal(secondLink.get("new_price")));
            assertThat(List.of(middle, finalPrice))
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyInAnyOrder(new BigDecimal("20.00"), new BigDecimal("30.00"));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private BigDecimal update(String value, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Los cambios de precio no iniciaron a tiempo.");
        }
        BigDecimal price = new BigDecimal(value);
        service.updatePrice(fixture.product(), new UpdateProductPriceRequest(price, value));
        return price;
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Pricing race', ?)",
                tenant, "pricing-race-" + tenant);
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
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, 10.00, 'published', true)
                """, product, tenant, "SKU-" + product, category, unit);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status)
                VALUES (?, ?, 'Pricing actor', ?, 'employee', 'active')
                """, user, tenant, "pricing-" + user + "@test.local");
        return new Fixture(tenant, product, user);
    }

    private static BigDecimal decimal(Object value) {
        return (BigDecimal) value;
    }

    private record Fixture(UUID tenant, UUID product, UUID user) {}
}
