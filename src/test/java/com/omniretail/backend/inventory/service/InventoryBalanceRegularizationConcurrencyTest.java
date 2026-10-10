package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La regularizacion frente a ventas, reservas y reintentos concurrentes. Cada escenario retiene un
 * bloqueo real en PostgreSQL y espera a que la otra sesion quede bloqueada antes de liberarlo, sin
 * dormir por tiempo fijo.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryBalanceRegularizationConcurrencyTest {

    @Autowired private InventoryBalanceRegularizationService regularizationService;
    @Autowired private InventoryStockService stockService;
    @Autowired private InventoryReservationLifecycleService lifecycleService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @Test
    void aSaleWaitingOnTheRegularizationLandsOnTheDestinationBalance() throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "5.000", "0.000");
        assign(fixture, shelf);
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID());
        CountDownLatch regularized = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        regularizationService.regularize(request);
                        regularized.countDown();
                        await(release);
                    }));
            await(regularized);
            Future<?> sale = pool.submit(() -> stockService.deductStock(new DeductStockCommand(
                    fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("3.000"),
                    "Venta", "POS_SALE", UUID.randomUUID(), UUID.randomUUID(), null)));
            awaitBlockedSessions(1);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            sale.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "12.000", "0.000");
    }

    @Test
    void aRegularizationWaitingOnAnInFlightConsumptionIsRejectedAsStaleAndChangesNothing()
            throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        // El reservado nace de reserve(); sembrarlo tambien lo duplicaria y dejaria 3 sin reserva activa.
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(new ReserveInventoryCommand(
                fixture.tenantId(), fixture.branchId(), fixture.productId(),
                InventoryReservationSourceType.transfer, UUID.randomUUID(), UUID.randomUUID(),
                null, null, new BigDecimal("3.000")));
        assign(fixture, shelf);
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID());
        CountDownLatch consumed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<LegacyBalanceRegularizationResultResponse> attempt;
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        lifecycleService.consume(fixture.tenantId(), reservation.getId());
                        consumed.countDown();
                        await(release);
                    }));
            await(consumed);
            attempt = pool.submit(() -> regularizationService.regularize(request));
            awaitBlockedSessions(1);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            try {
                attempt.get(20, TimeUnit.SECONDS);
                throw new AssertionError("La regularizacion debia rechazarse como obsoleta.");
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class,
                        business -> assertThat(business.getCode())
                                .isEqualTo(InventoryBalanceRegularizationService.STALE_SNAPSHOT_CODE));
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertBalance(nullBalance, "7.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                        Long.class, fixture.tenantId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ? AND location_id = ?",
                        Long.class, fixture.tenantId(), shelf))
                .isZero();
    }

    @Test
    void theSameRequestSentConcurrentlyIsAppliedOnceAndTheOtherGetsThePersistedResult()
            throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        UUID shelfBalance = balance(fixture, shelf, "5.000", "0.000");
        assign(fixture, shelf);
        RegularizeLegacyBalanceRequest request = request(fixture, shelf, UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<LegacyBalanceRegularizationResultResponse> results;
        try {
            Future<LegacyBalanceRegularizationResultResponse> first = pool.submit(
                    () -> whenReady(ready, start, request));
            Future<LegacyBalanceRegularizationResultResponse> second = pool.submit(
                    () -> whenReady(ready, start, request));
            await(ready);
            start.countDown();
            results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        assertThat(results).filteredOn(LegacyBalanceRegularizationResultResponse::idempotent).hasSize(1);
        assertThat(results.get(0).regularizationId()).isEqualTo(results.get(1).regularizationId());
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "15.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                        Long.class, fixture.tenantId()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ? AND type = 'transfer'",
                        Long.class, fixture.tenantId()))
                .isOne();
    }

    // ------------------------------------------------------------ utilidades

    private LegacyBalanceRegularizationResultResponse whenReady(
            CountDownLatch ready, CountDownLatch start, RegularizeLegacyBalanceRequest request) {
        ready.countDown();
        await(start);
        return regularizationService.regularize(request);
    }

    private RegularizeLegacyBalanceRequest request(Fixture fixture, UUID location, UUID key) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, null,
                fixture.branchId(), UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        LegacyBalanceRegularizationPreviewResponse preview =
                regularizationService.preview(fixture.branchId(), fixture.productId(), location);
        return new RegularizeLegacyBalanceRequest(
                fixture.branchId(), fixture.productId(), location, key, "Regularizacion concurrente",
                preview.sourceQuantity(), preview.sourceReservedQuantity(),
                preview.destinationQuantity(), preview.snapshotFingerprint());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La senal de la prueba no llego a tiempo.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Espera, sin dormir por tiempo fijo, a que PostgreSQL reporte sesiones esperando un bloqueo. */
    private void awaitBlockedSessions(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (blocked != null && blocked >= expected) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new IllegalStateException("Ninguna sesion quedo esperando un bloqueo.");
    }

    private Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbc.update(
                "INSERT INTO business_capabilities_configs "
                        + "(id, tenant_id, preset, supports_multiple_locations) VALUES (?, ?, 'custom', true)",
                UUID.randomUUID(), tenantId);
        jdbc.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "categoria-" + suffix);
        jdbc.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbc.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, product_type, "
                        + "tracking_stock) VALUES (?, ?, ?, ?, ?, ?, 'physical', true)",
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix, categoryId, unitId);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) "
                        + "VALUES (?, ?, 'Administrador', ?, 'employee', 'active', ?)",
                userId, tenantId, userId + "@test.local", branchId);
        return new Fixture(tenantId, branchId, productId, userId);
    }

    private UUID location(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'Estante', 'warehouse', 'active')",
                id, fixture.tenantId(), fixture.branchId(), "LOC-" + id);
        return id;
    }

    private void assign(Fixture fixture, UUID locationId) {
        jdbc.update(
                "INSERT INTO product_inventory_settings "
                        + "(tenant_id, branch_id, product_id, default_location_id) VALUES (?, ?, ?, ?)",
                fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private UUID balance(Fixture fixture, UUID locationId, String quantity, String reserved) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO inventory_balances "
                        + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId,
                new BigDecimal(quantity), new BigDecimal(reserved));
        return id;
    }

    private void assertBalance(UUID balanceId, String quantity, String reserved) {
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory_balances WHERE id = ?", BigDecimal.class, balanceId))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                        BigDecimal.class, balanceId))
                .isEqualByComparingTo(reserved);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID userId) {}
}
