package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
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
class InventoryStockServiceConcurrencyTest {

    @Autowired
    private InventoryStockService inventoryStockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentDeductionsAllowExactlyOneOutMovement() throws Exception {
        Fixture fixture = createFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Outcome> first = executor.submit(() -> deductOnce(fixture, ready, start));
            Future<Outcome> second = executor.submit(() -> deductOnce(fixture, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(outcome -> !outcome.succeeded())
                    .singleElement()
                    .extracting(Outcome::errorCode)
                    .isEqualTo("INSUFFICIENT_STOCK");

            BigDecimal finalQuantity = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM inventory_balances WHERE id = ?",
                    BigDecimal.class,
                    fixture.balanceId());
            assertThat(finalQuantity).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(finalQuantity).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(countMovements(fixture)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome deductOnce(
            Fixture fixture, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las deducciones concurrentes no iniciaron a tiempo.");
        }
        try {
            inventoryStockService.deductStock(
                    fixture.tenantId(),
                    fixture.branchId(),
                    fixture.productId(),
                    BigDecimal.ONE);
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private long countMovements(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND type = 'out'
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
        return count == null ? 0 : count;
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """,
                branchId,
                tenantId,
                "MAIN-" + suffix,
                "Principal " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, ?, ?, 'active')
                """,
                categoryId,
                tenantId,
                "Categoria " + suffix,
                "categoria-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
                """,
                unitId,
                tenantId,
                "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, NULL, 1.000, 0.000)
                """,
                balanceId,
                tenantId,
                branchId,
                productId);

        return new Fixture(tenantId, branchId, productId, balanceId);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID balanceId) {}

    private record Outcome(boolean succeeded, String errorCode) {}
}
