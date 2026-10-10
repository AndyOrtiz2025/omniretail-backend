package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest;
import com.omniretail.backend.inventory.dto.ReconcileInventoryCountRequest.LotCount;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
class InventoryCountServiceConcurrencyTest {

    @Autowired private InventoryCountService countService;
    @Autowired private InventoryStockService stockService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private TenantCapabilityGuard tenantCapabilityGuard;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowAllBranches() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void simultaneousReconciliationsHaveOneWinnerAndOneStaleSnapshot() throws Exception {
        Fixture fixture = fixture();
        UUID lot = insertStock(fixture, "2", "0");
        ReconcileInventoryCountRequest request = request(fixture, lot, "2", "1");

        List<Outcome> outcomes = race(
                () -> countService.reconcile(request),
                () -> countService.reconcile(request));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::code)
                .isEqualTo("COUNT_SNAPSHOT_STALE");
        assertThat(quantity(fixture)).isEqualByComparingTo("1");
        assertThat(movementCount(fixture)).isOne();
    }

    @Test
    void reconciliationAgainstConcurrentReservationNeverDropsBelowReserved() throws Exception {
        Fixture fixture = fixture();
        UUID lot = insertStock(fixture, "1", "0");
        ReconcileInventoryCountRequest request = request(fixture, lot, "1", "0");

        List<Outcome> outcomes = race(
                () -> countService.reconcile(request),
                () -> stockService.reserveStock(
                        fixture.tenant(), fixture.branch(), fixture.product(), BigDecimal.ONE));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::code)
                .isIn("COUNT_BELOW_RESERVED", "INSUFFICIENT_STOCK");
        BigDecimal quantity = quantity(fixture);
        BigDecimal reserved = reserved(fixture);
        assertThat(quantity).isGreaterThanOrEqualTo(reserved);
        assertThat(quantity).satisfiesAnyOf(
                value -> assertThat(value).isEqualByComparingTo(BigDecimal.ZERO),
                value -> assertThat(value).isEqualByComparingTo(BigDecimal.ONE));
    }

    private List<Outcome> race(Action firstAction, Action secondAction) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> run(firstAction, ready, start));
            Future<Outcome> second = executor.submit(() -> run(secondAction, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Outcome run(Action action, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las operaciones concurrentes no iniciaron a tiempo.");
        }
        try {
            action.run();
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private Fixture fixture() {
        Fixture fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Count race', ?)",
                fixture.tenant(), "count-race-" + suffix);
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, 'Principal', 'main', 'active')",
                fixture.branch(), fixture.tenant(), "B-" + suffix);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Categoria', ?)",
                fixture.category(), fixture.tenant(), "cat-" + suffix);
        jdbc.update("INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')",
                fixture.unit(), fixture.tenant(), "U-" + suffix.substring(0, 8));
        jdbc.update("INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, product_type, tracking_stock, tracking_lot) VALUES (?, ?, ?, 'Producto conteo', ?, ?, 'physical', true, true)",
                fixture.product(), fixture.tenant(), "SKU-" + suffix,
                fixture.category(), fixture.unit());
        jdbc.update("INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')",
                fixture.location(), fixture.tenant(), fixture.branch(), "L-" + suffix);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) VALUES (?, ?, 'Contador', ?, 'employee', 'active', ?)",
                fixture.user(), fixture.tenant(), fixture.user() + "@test.local", fixture.branch());
        jdbc.update("INSERT INTO business_capabilities_configs (id, tenant_id, preset, supports_multiple_locations) VALUES (?, ?, 'custom', true)",
                UUID.randomUUID(), fixture.tenant());
        jdbc.update("INSERT INTO product_inventory_settings (tenant_id, branch_id, product_id, default_location_id) VALUES (?, ?, ?, ?)",
                fixture.tenant(), fixture.branch(), fixture.product(), fixture.location());
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee, null,
                fixture.branch(), UUID.randomUUID()));
        return fixture;
    }

    private UUID insertStock(Fixture fixture, String quantity, String reserved) {
        UUID lot = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, 'LOT-RACE')",
                lot, fixture.tenant(), fixture.product());
        jdbc.update("INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), fixture.tenant(), fixture.branch(), fixture.product(),
                fixture.location(), quantity, reserved);
        jdbc.update("INSERT INTO inventory_lot_balances (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), fixture.tenant(), fixture.branch(), fixture.location(),
                lot, quantity, reserved);
        return lot;
    }

    private ReconcileInventoryCountRequest request(
            Fixture fixture, UUID lot, String expected, String counted) {
        return new ReconcileInventoryCountRequest(
                fixture.branch(), fixture.product(), null, "Conteo concurrente",
                new BigDecimal(expected),
                List.of(new LotCount(
                        lot, new BigDecimal(expected), new BigDecimal(counted), null, null)),
                null, null, null);
    }

    private BigDecimal quantity(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND product_id = ?",
                BigDecimal.class, fixture.tenant(), fixture.product());
    }

    private BigDecimal reserved(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM inventory_balances WHERE tenant_id = ? AND product_id = ?",
                BigDecimal.class, fixture.tenant(), fixture.product());
    }

    private long movementCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND product_id = ?",
                Long.class, fixture.tenant(), fixture.product());
    }

    @FunctionalInterface
    private interface Action {
        void run();
    }

    private record Outcome(boolean succeeded, String code) {}

    private record Fixture(
            UUID tenant,
            UUID branch,
            UUID category,
            UUID unit,
            UUID product,
            UUID location,
            UUID user) {}
}
