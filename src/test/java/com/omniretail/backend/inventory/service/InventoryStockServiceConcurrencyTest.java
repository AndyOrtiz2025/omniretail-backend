package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.inventory.dto.AddStockCommand;
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
    void concurrentIncrementsCreateOneBalanceWithoutLostUpdate() throws Exception {
        Fixture fixture = createFixtureWithoutBalance();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Outcome> first = executor.submit(() -> incrementOnce(fixture, ready, start));
            Future<Outcome> second = executor.submit(() -> incrementOnce(fixture, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(countBalances(fixture)).isOne();
            assertThat(defaultBalanceQuantity(fixture)).isEqualByComparingTo("10.000");
            assertThat(countMovements(fixture, "in")).isEqualTo(2);
            assertThat(sumMovementQuantity(fixture, "in")).isEqualByComparingTo("10.000");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLocationIncrementsCreateOneBalanceWithoutLostUpdate() throws Exception {
        Fixture fixture = createFixtureWithoutBalance();
        UUID locationId = createLocation(fixture);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Outcome> first = executor.submit(() -> incrementOnceAtLocation(fixture, locationId, ready, start));
            Future<Outcome> second = executor.submit(() -> incrementOnceAtLocation(fixture, locationId, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(countLocationBalances(fixture, locationId)).isOne();
            assertThat(locationBalanceQuantity(fixture, locationId)).isEqualByComparingTo("10.000");
            assertThat(countMovementsToLocation(fixture, locationId)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

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

    private Outcome incrementOnce(
            Fixture fixture, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Los incrementos concurrentes no iniciaron a tiempo.");
        }
        try {
            inventoryStockService.incrementStock(new AddStockCommand(
                    fixture.tenantId(),
                    fixture.branchId(),
                    fixture.productId(),
                    new BigDecimal("5.000"),
                    "Conteo concurrente",
                    "MANUAL_ADJUSTMENT",
                    UUID.randomUUID(),
                    null));
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private Outcome incrementOnceAtLocation(
            Fixture fixture, UUID locationId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Los incrementos por ubicacion no iniciaron a tiempo.");
        }
        try {
            inventoryStockService.incrementStockAtLocation(
                    new AddStockCommand(
                            fixture.tenantId(),
                            fixture.branchId(),
                            fixture.productId(),
                            new BigDecimal("5.000"),
                            "Recepcion concurrente",
                            "GOODS_RECEIPT",
                            UUID.randomUUID(),
                            null),
                    locationId);
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private long countMovements(Fixture fixture) {
        return countMovements(fixture, "out");
    }

    private long countMovements(Fixture fixture, String type) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND type = ?
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                type);
        return count == null ? 0 : count;
    }

    private long countBalances(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
        return count == null ? 0 : count;
    }

    private BigDecimal defaultBalanceQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                SELECT quantity
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
    }

    private BigDecimal sumMovementQuantity(Fixture fixture, String type) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(sum(quantity), 0)
                FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND type = ?
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                type);
    }

    private UUID createLocation(Fixture fixture) {
        UUID locationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega concurrente', 'warehouse', 'active')
                """,
                locationId,
                fixture.tenantId(),
                fixture.branchId(),
                "LOC-" + locationId);
        return locationId;
    }

    private long countLocationBalances(Fixture fixture, UUID locationId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId);
        return count == null ? 0 : count;
    }

    private BigDecimal locationBalanceQuantity(Fixture fixture, UUID locationId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId);
    }

    private long countMovementsToLocation(Fixture fixture, UUID locationId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                  AND type = 'in' AND to_location_id = ?
                """,
                Long.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId);
        return count == null ? 0 : count;
    }

    private Fixture createFixture() {
        return createFixture(true);
    }

    private Fixture createFixtureWithoutBalance() {
        return createFixture(false);
    }

    private Fixture createFixture(boolean withBalance) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID balanceId = withBalance ? UUID.randomUUID() : null;
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
        if (withBalance) {
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
        }

        return new Fixture(tenantId, branchId, productId, balanceId);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID balanceId) {}

    private record Outcome(boolean succeeded, String errorCode) {}
}
