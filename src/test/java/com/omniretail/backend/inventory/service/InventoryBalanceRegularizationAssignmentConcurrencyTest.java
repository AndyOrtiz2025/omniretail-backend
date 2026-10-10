package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.inventory.dto.DeductStockCommand;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationPreviewResponse;
import com.omniretail.backend.inventory.dto.LegacyBalanceRegularizationResultResponse;
import com.omniretail.backend.inventory.dto.RegularizeLegacyBalanceRequest;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * La asignacion inicial frente a ventas, reservas, movimientos con balance bloqueado, actualizaciones de
 * configuracion y solicitudes simultaneas. Cada escenario retiene un bloqueo real en PostgreSQL y espera a
 * que la otra sesion quede bloqueada antes de liberarlo, sin dormir por tiempo fijo.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryBalanceRegularizationAssignmentConcurrencyTest {

    private static final UUID ROLE_ID = UUID.randomUUID();

    @Autowired private InventoryBalanceRegularizationService regularizationService;
    @Autowired private InventorySettingsService settingsService;
    @Autowired private InventoryStockService stockService;
    @Autowired private InventoryReservationLifecycleService lifecycleService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;
    @MockitoBean private PermissionResolver permissionResolver;

    @Test
    void aSaleWaitingOnTheAssignmentIsServedFromTheNewLocation() throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(fixture, shelf);
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        regularizationService.regularize(request);
                        applied.countDown();
                        await(release);
                    }));
            await(applied);
            Future<?> sale = pool.submit(() -> stockService.deductStock(new DeductStockCommand(
                    fixture.tenantId(), fixture.branchId(), fixture.productId(), new BigDecimal("3.000"),
                    "Venta", "POS_SALE", UUID.randomUUID(), UUID.randomUUID(), null)));
            // La venta decide la ubicacion bajo FOR SHARE del producto: espera al NO KEY UPDATE de la asignacion.
            awaitBlockedSessions(1);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            sale.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // Sin la exclusion, la venta habria leido "sin asignacion" y descontado del balance NULL.
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(balanceId(fixture, shelf), "7.000", "0.000");
        assertThat(assignedInDb(fixture)).isEqualTo(shelf);
    }

    @Test
    void aReservationWaitingOnTheAssignmentIsCreatedOnTheDestination() throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(fixture, shelf);
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        regularizationService.regularize(request);
                        applied.countDown();
                        await(release);
                    }));
            await(applied);
            Future<?> reserve = pool.submit(() -> lifecycleService.reserve(new ReserveInventoryCommand(
                    fixture.tenantId(), fixture.branchId(), fixture.productId(),
                    InventoryReservationSourceType.transfer, UUID.randomUUID(), UUID.randomUUID(),
                    null, null, new BigDecimal("4.000"))));
            awaitBlockedSessions(1);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            reserve.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        UUID shelfBalance = balanceId(fixture, shelf);
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(shelfBalance, "10.000", "4.000");
        assertThat(jdbc.queryForObject(
                        "SELECT allocations -> 0 ->> 'balanceId' FROM inventory_reservations "
                                + "WHERE tenant_id = ? AND product_id = ?",
                        String.class, fixture.tenantId(), fixture.productId()))
                .isEqualTo(shelfBalance.toString());
    }

    /**
     * Un flujo que ya tiene el balance bloqueado e inserta un movimiento (KEY SHARE sobre el producto) no
     * forma un ciclo con la asignacion: con FOR UPDATE sobre el producto, esta transaccion esperaria el
     * balance y el otro flujo esperaria el producto (deadlock); con NO KEY UPDATE ambos terminan.
     */
    @Test
    void aFlowHoldingTheBalanceAndInsertingAMovementDoesNotDeadlockWithTheAssignment() throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(fixture, shelf);
        CountDownLatch balanceLocked = new CountDownLatch(1);
        CountDownLatch insertNow = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<LegacyBalanceRegularizationResultResponse> assignment;
        try {
            Future<?> otherFlow = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForList(
                                "SELECT id FROM inventory_balances WHERE id = ? FOR UPDATE", nullBalance);
                        balanceLocked.countDown();
                        await(insertNow);
                        jdbc.update(
                                "INSERT INTO inventory_movements "
                                        + "(id, tenant_id, branch_id, product_id, type, reason, quantity, "
                                        + "created_at) VALUES (?, ?, ?, ?, 'in', 'Movimiento concurrente', 1, "
                                        + "now())",
                                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(),
                                fixture.productId());
                    }));
            await(balanceLocked);
            assignment = pool.submit(() -> regularizationService.regularize(request));
            // La asignacion ya tiene el producto (NO KEY UPDATE) y espera el balance del otro flujo.
            awaitBlockedSessions(1);
            insertNow.countDown();
            otherFlow.get(20, TimeUnit.SECONDS);
            LegacyBalanceRegularizationResultResponse result = assignment.get(20, TimeUnit.SECONDS);
            assertThat(result.assignmentApplied()).isTrue();
        } finally {
            insertNow.countDown();
            pool.shutdownNow();
        }

        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(balanceId(fixture, shelf), "10.000", "0.000");
        assertThat(assignedInDb(fixture)).isEqualTo(shelf);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_movements WHERE tenant_id = ?",
                        Long.class, fixture.tenantId()))
                .isEqualTo(2);
    }

    @Test
    void aSettingsUpdateWaitingOnTheAssignmentCannotMoveItAwayFromTheConsolidatedStock() throws Exception {
        Fixture fixture = fixture();
        UUID shelfA = location(fixture);
        UUID shelfB = location(fixture);
        balance(fixture, null, "10.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(fixture, shelfA);
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> update;
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        regularizationService.regularize(request);
                        applied.countDown();
                        await(release);
                    }));
            await(applied);
            update = pool.submit(() -> settingsService.upsert(
                    fixture.branchId(), fixture.productId(),
                    new UpdateInventorySettingsRequest(BigDecimal.ONE, null, shelfB)));
            // PUT settings toma el producto FOR UPDATE: espera a la asignacion.
            awaitBlockedSessions(1);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            try {
                update.get(20, TimeUnit.SECONDS);
                throw new AssertionError("El cambio a otra ubicacion debia rechazarse.");
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class,
                        business -> assertThat(business.getCode())
                                .isEqualTo(InventoryOperationalLocationService.LOCATION_CHANGE_BLOCKED_CODE));
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(assignedInDb(fixture)).isEqualTo(shelfA);
        assertBalance(balanceId(fixture, shelfA), "10.000", "0.000");
    }

    @Test
    void twoAssignmentsToDifferentLocationsHaveASingleWinner() throws Exception {
        Fixture fixture = fixture();
        UUID shelfA = location(fixture);
        UUID shelfB = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        RegularizeLegacyBalanceRequest toA = request(fixture, shelfA);
        RegularizeLegacyBalanceRequest toB = request(fixture, shelfB);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Outcome> outcomes = new ArrayList<>();
        try {
            Future<Outcome> first = pool.submit(() -> attempt(ready, start, toA));
            Future<Outcome> second = pool.submit(() -> attempt(ready, start, toB));
            await(ready);
            start.countDown();
            outcomes.add(first.get(30, TimeUnit.SECONDS));
            outcomes.add(second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::code)
                .isEqualTo(InventoryBalanceRegularizationService.ASSIGNMENT_CONFLICT_CODE);
        UUID winner = assignedInDb(fixture);
        assertThat(winner).isIn(shelfA, shelfB);
        UUID loser = winner.equals(shelfA) ? shelfB : shelfA;
        assertBalance(nullBalance, "0.000", "0.000");
        assertBalance(balanceId(fixture, winner), "10.000", "0.000");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ? AND location_id = ?",
                        Long.class, fixture.tenantId(), loser))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                        Long.class, fixture.tenantId()))
                .isOne();
    }

    @Test
    void aBusyProductAnswersWithAReintentableConflictAndLeavesNothingApplied() throws Exception {
        Fixture fixture = fixture();
        UUID shelf = location(fixture);
        UUID nullBalance = balance(fixture, null, "10.000", "0.000");
        RegularizeLegacyBalanceRequest request = request(fixture, shelf);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Una decision en curso (FOR SHARE del producto) que no termina antes del lock_timeout.
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForList(
                                "SELECT id FROM products WHERE tenant_id = ? AND id = ? FOR SHARE",
                                fixture.tenantId(), fixture.productId());
                        holding.countDown();
                        await(release);
                    }));
            await(holding);
            Future<LegacyBalanceRegularizationResultResponse> blocked =
                    pool.submit(() -> regularizationService.regularize(request));
            try {
                blocked.get(30, TimeUnit.SECONDS);
                throw new AssertionError("La asignacion debia agotar el tiempo de espera.");
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class,
                        business -> assertThat(business.getCode())
                                .isEqualTo(InventoryBalanceRegularizationService.BUSY_CODE));
            }
            assertThat(assignedInDb(fixture)).isNull();
            assertBalance(nullBalance, "10.000", "0.000");
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM inventory_balance_regularizations WHERE tenant_id = ?",
                            Long.class, fixture.tenantId()))
                    .isZero();

            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            // Reintentable con la misma clave y el mismo cuerpo: no habia quedado registro.
            LegacyBalanceRegularizationResultResponse retried = regularizationService.regularize(request);
            assertThat(retried.idempotent()).isFalse();
            assertThat(retried.assignmentApplied()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertBalance(nullBalance, "0.000", "0.000");
        assertThat(assignedInDb(fixture)).isEqualTo(shelf);
    }

    // ------------------------------------------------------------ utilidades

    private record Outcome(boolean succeeded, String code) {}

    private Outcome attempt(
            CountDownLatch ready, CountDownLatch start, RegularizeLegacyBalanceRequest request) {
        ready.countDown();
        await(start);
        try {
            regularizationService.regularize(request);
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private RegularizeLegacyBalanceRequest request(Fixture fixture, UUID location) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, ROLE_ID, fixture.branchId(),
                UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), eq("inventory.adjustment.create")))
                .willReturn(true);
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), eq("catalog.products.update")))
                .willReturn(true);
        LegacyBalanceRegularizationPreviewResponse preview = regularizationService.preview(
                fixture.branchId(), fixture.productId(), location, true);
        return new RegularizeLegacyBalanceRequest(
                fixture.branchId(), fixture.productId(), location, UUID.randomUUID(),
                "Asignacion inicial concurrente", preview.sourceQuantity(), preview.sourceReservedQuantity(),
                preview.destinationQuantity(), preview.snapshotFingerprint(), true);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
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

    private UUID balanceId(Fixture fixture, UUID locationId) {
        return jdbc.queryForObject(
                "SELECT id FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? "
                        + "AND product_id = ? AND location_id = ?",
                UUID.class, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private UUID assignedInDb(Fixture fixture) {
        List<UUID> rows = jdbc.query(
                "SELECT default_location_id FROM product_inventory_settings "
                        + "WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                (resultSet, row) -> resultSet.getObject(1, UUID.class),
                fixture.tenantId(), fixture.branchId(), fixture.productId());
        return rows.isEmpty() ? null : rows.getFirst();
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
