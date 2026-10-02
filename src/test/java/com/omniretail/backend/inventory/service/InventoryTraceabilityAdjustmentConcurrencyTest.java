package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentType;
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
class InventoryTraceabilityAdjustmentConcurrencyTest {

    @Autowired
    private InventoryTraceabilityAdjustmentService adjustmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private TenantCapabilityGuard tenantCapabilityGuard;

    @MockitoBean
    private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void allowAllBranches() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void concurrentAggregateOutAllowsOneWinnerAndNeverGoesNegative() throws Exception {
        Fixture fixture = createFixture(false, false);
        authenticate(fixture);
        insertAggregate(fixture, fixture.firstLocationId(), "1.000");

        List<Outcome> outcomes = runConcurrently(
                request(fixture, fixture.firstLocationId(), InventoryAdjustmentType.out,
                        null, null, List.of()),
                request(fixture, fixture.firstLocationId(), InventoryAdjustmentType.out,
                        null, null, List.of()));

        assertOneWinner(outcomes, "INSUFFICIENT_STOCK");
        assertThat(aggregateQuantity(fixture, fixture.firstLocationId()))
                .isEqualByComparingTo(BigDecimal.ZERO)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(movementCount(fixture)).isOne();
    }

    @Test
    void concurrentLotOutAllowsOneWinnerAndLotNeverGoesNegative() throws Exception {
        Fixture fixture = createFixture(true, false);
        authenticate(fixture);
        insertAggregate(fixture, fixture.firstLocationId(), "2.000");
        UUID lotId = insertLot(fixture, "LOT-OUT");
        insertLotBalance(fixture, lotId, fixture.firstLocationId(), "1.000");

        InventoryAdjustmentRequest request = request(
                fixture, fixture.firstLocationId(), InventoryAdjustmentType.out,
                lotId, null, List.of());
        List<Outcome> outcomes = runConcurrently(request, request);

        assertOneWinner(outcomes, "INSUFFICIENT_TRACEABLE_LOT_STOCK");
        assertThat(aggregateQuantity(fixture, fixture.firstLocationId())).isEqualByComparingTo("1.000");
        assertThat(lotQuantity(fixture, lotId, fixture.firstLocationId()))
                .isEqualByComparingTo(BigDecimal.ZERO)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(movementCount(fixture)).isOne();
    }

    @Test
    void concurrentSerialOutAllowsOneWinnerAndWritesSerialOffOnce() throws Exception {
        Fixture fixture = createFixture(false, true);
        authenticate(fixture);
        insertAggregate(fixture, fixture.firstLocationId(), "2.000");
        insertSerial(fixture, fixture.firstLocationId(), "SER-OUT");

        InventoryAdjustmentRequest request = request(
                fixture, fixture.firstLocationId(), InventoryAdjustmentType.out,
                null, null, List.of("SER-OUT"));
        List<Outcome> outcomes = runConcurrently(request, request);

        assertOneWinner(outcomes, "SERIAL_UNAVAILABLE");
        assertThat(aggregateQuantity(fixture, fixture.firstLocationId())).isEqualByComparingTo("1.000");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM inventory_serials WHERE tenant_id = ? AND product_id = ? AND serial_number = 'SER-OUT'",
                        String.class, fixture.tenantId(), fixture.productId()))
                .isEqualTo("WRITTEN_OFF");
        assertThat(movementCount(fixture)).isOne();
    }

    @Test
    void concurrentLotInAcrossLocationsCreatesOneLotAndTwoBalances() throws Exception {
        Fixture fixture = createFixture(true, false);
        authenticate(fixture);

        List<Outcome> outcomes = runConcurrently(
                request(fixture, fixture.firstLocationId(), InventoryAdjustmentType.in,
                        null, "SAME-LOT", List.of()),
                request(fixture, fixture.secondLocationId(), InventoryAdjustmentType.in,
                        null, "SAME-LOT", List.of()));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_lots WHERE tenant_id = ? AND product_id = ? AND lot_number = 'SAME-LOT'",
                        Long.class, fixture.tenantId(), fixture.productId()))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_lot_balances WHERE tenant_id = ? AND branch_id = ?",
                        Long.class, fixture.tenantId(), fixture.branchId()))
                .isEqualTo(2L);
        assertThat(movementCount(fixture)).isEqualTo(2);
    }

    @Test
    void concurrentSerialInAcrossLocationsCreatesOneSerialAndRollsBackLoser() throws Exception {
        Fixture fixture = createFixture(false, true);
        authenticate(fixture);

        List<Outcome> outcomes = runConcurrently(
                request(fixture, fixture.firstLocationId(), InventoryAdjustmentType.in,
                        null, null, List.of("SAME-SERIAL")),
                request(fixture, fixture.secondLocationId(), InventoryAdjustmentType.in,
                        null, null, List.of("SAME-SERIAL")));

        assertOneWinner(outcomes, "DUPLICATE_SERIAL");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_serials WHERE tenant_id = ? AND product_id = ? AND serial_number = 'SAME-SERIAL'",
                        Long.class, fixture.tenantId(), fixture.productId()))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT coalesce(sum(quantity), 0) FROM inventory_balances WHERE tenant_id = ? AND product_id = ?",
                        BigDecimal.class, fixture.tenantId(), fixture.productId()))
                .isEqualByComparingTo("1.000");
        assertThat(movementCount(fixture)).isOne();
    }

    private List<Outcome> runConcurrently(
            InventoryAdjustmentRequest firstRequest, InventoryAdjustmentRequest secondRequest) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> adjustOnce(firstRequest, ready, start));
            Future<Outcome> second = executor.submit(() -> adjustOnce(secondRequest, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome adjustOnce(
            InventoryAdjustmentRequest request, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Los ajustes concurrentes no iniciaron a tiempo.");
        }
        try {
            adjustmentService.adjust(request);
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private static void assertOneWinner(List<Outcome> outcomes, String loserCode) {
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::errorCode)
                .isEqualTo(loserCode);
    }

    private Fixture createFixture(boolean trackingLot, boolean trackingSerial) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID firstLocationId = UUID.randomUUID();
        UUID secondLocationId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId, "Tenant " + suffix, "tenant-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId, tenantId, "MAIN-" + suffix, "Principal " + suffix);
        jdbcTemplate.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "categoria-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     tracking_stock, tracking_lot, tracking_expiration, tracking_serial)
                VALUES (?, ?, ?, ?, 'physical', ?, ?, true, ?, false, ?)
                """,
                productId, tenantId, "SKU-" + suffix, "Producto " + suffix,
                categoryId, unitId, trackingLot, trackingSerial);
        insertLocation(tenantId, branchId, firstLocationId, "LOC-A-" + suffix);
        insertLocation(tenantId, branchId, secondLocationId, "LOC-B-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) VALUES (?, ?, ?, ?, 'employee', 'active', ?)",
                userId, tenantId, "Usuario " + suffix, "user-" + suffix + "@test.local", branchId);
        return new Fixture(
                tenantId, branchId, productId, userId, firstLocationId, secondLocationId);
    }

    private void authenticate(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee, null,
                fixture.branchId(), UUID.randomUUID()));
    }

    private void insertLocation(UUID tenantId, UUID branchId, UUID locationId, String code) {
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')",
                locationId, tenantId, branchId, code, "Ubicacion " + code);
    }

    private InventoryAdjustmentRequest request(
            Fixture fixture,
            UUID locationId,
            InventoryAdjustmentType type,
            UUID lotId,
            String lotNumber,
            List<String> serialNumbers) {
        return new InventoryAdjustmentRequest(
                fixture.branchId(), fixture.productId(), type, BigDecimal.ONE,
                "Ajuste concurrente", "MANUAL_ADJUSTMENT", UUID.randomUUID(), locationId,
                lotId, lotNumber, null, serialNumbers);
    }

    private void insertAggregate(Fixture fixture, UUID locationId, String quantity) {
        jdbcTemplate.update(
                "INSERT INTO inventory_balances (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), fixture.productId(),
                locationId, quantity);
    }

    private UUID insertLot(Fixture fixture, String number) {
        UUID lotId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO inventory_lots (id, tenant_id, product_id, lot_number) VALUES (?, ?, ?, ?)",
                lotId, fixture.tenantId(), fixture.productId(), number);
        return lotId;
    }

    private void insertLotBalance(Fixture fixture, UUID lotId, UUID locationId, String quantity) {
        jdbcTemplate.update(
                "INSERT INTO inventory_lot_balances (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?, ?::numeric, 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId, lotId, quantity);
    }

    private void insertSerial(Fixture fixture, UUID locationId, String number) {
        jdbcTemplate.update(
                "INSERT INTO inventory_serials (id, tenant_id, branch_id, location_id, product_id, serial_number, status, version) VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE', 0)",
                UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), locationId,
                fixture.productId(), number);
    }

    private BigDecimal aggregateQuantity(Fixture fixture, UUID locationId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), fixture.productId(), locationId);
    }

    private BigDecimal lotQuantity(Fixture fixture, UUID lotId, UUID locationId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_lot_balances WHERE tenant_id = ? AND branch_id = ? AND lot_id = ? AND location_id = ?",
                BigDecimal.class, fixture.tenantId(), fixture.branchId(), lotId, locationId);
    }

    private long movementCount(Fixture fixture) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ? AND branch_id = ? AND product_id = ?",
                Long.class, fixture.tenantId(), fixture.branchId(), fixture.productId());
        return value == null ? 0 : value;
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID userId,
            UUID firstLocationId,
            UUID secondLocationId) {}

    private record Outcome(boolean succeeded, String errorCode) {}
}
