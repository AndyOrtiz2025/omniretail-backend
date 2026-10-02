package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.inventory.dto.ReserveInventoryCommand;
import com.omniretail.backend.shared.exception.BusinessException;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.jpa.repository.Lock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryReservationLifecycleServiceTest {

    @Autowired private InventoryReservationLifecycleService lifecycleService;
    @Autowired private InventoryReservationRepository reservations;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void reserveIncrementsReservedQuantityWithoutChangingPhysicalQuantity() {
        Fixture fixture = createFixture("5.000", "0.000");

        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));

        assertBalance(fixture, "5.000", "2.000");
        assertThat(reservation.getStatus()).isEqualTo(InventoryReservationStatus.active);
        assertThat(reservation.getSourceType()).isEqualTo(InventoryReservationSourceType.transfer);
        assertThat(reservation.getSourceId()).isEqualTo(fixture.sourceId());
        assertThat(reservation.getSourceLineId()).isEqualTo(fixture.sourceLineId());
        assertThat(reservation.getQuantity()).isEqualByComparingTo("2.000");
        assertThat(reservation.getOrderId()).isNull();
        assertThat(reservation.getOrderItemId()).isNull();
        assertThat(reservation.getAllocations())
                .contains(
                        fixture.balanceId().toString(),
                        "\"locationId\":null",
                        "\"reservedQuantity\"",
                        "\"consumedQuantity\":0");
    }

    @Test
    void reserveRejectsInsufficientAvailableStock() {
        Fixture fixture = createFixture("5.000", "4.000");

        assertThatThrownBy(() -> lifecycleService.reserve(command(fixture, "2.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INSUFFICIENT_STOCK");
        assertBalance(fixture, "5.000", "4.000");
    }

    @Test
    void duplicateSourceLineDoesNotReserveStockTwice() {
        Fixture fixture = createFixture("5.000", "0.000");
        lifecycleService.reserve(command(fixture, "2.000"));

        assertThatThrownBy(() -> lifecycleService.reserve(command(fixture, "2.000")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVENTORY_RESERVATION_ALREADY_EXISTS");
        assertBalance(fixture, "5.000", "2.000");
    }

    @Test
    void consumeChangesPhysicalAndReservedQuantitiesExactlyOnce() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));

        lifecycleService.consume(fixture.tenantId(), reservation.getId());
        lifecycleService.consume(fixture.tenantId(), reservation.getId());

        assertBalance(fixture, "3.000", "0.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.consumed);
    }

    @Test
    void releaseChangesOnlyReservedQuantityExactlyOnce() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));

        lifecycleService.release(fixture.tenantId(), reservation.getId());
        lifecycleService.release(fixture.tenantId(), reservation.getId());

        assertBalance(fixture, "5.000", "0.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.released);
    }

    @Test
    void releaseOnlySubtractsTheUnconsumedAllocationQuantity() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "5.000"));
        jdbcTemplate.update("""
                UPDATE inventory_reservations
                SET allocations = jsonb_set(allocations, '{0,consumedQuantity}', '2.000'::jsonb)
                WHERE id = ?
                """, reservation.getId());

        lifecycleService.release(fixture.tenantId(), reservation.getId());

        assertBalance(fixture, "5.000", "2.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.released);
    }

    @Test
    void consumeUsesTheExactAllocatedBalanceWhenTheProductHasTwoBalances() {
        Fixture fixture = createFixture("10.000", "0.000");
        UUID secondBalance = createLocatedBalance(fixture, "8.000", "3.000");
        InventoryReservation reservation = persistReservation(
                fixture, "3.000", allocations(allocation(secondBalance, "3.000", "0.000")));

        lifecycleService.consume(fixture.tenantId(), reservation.getId());

        assertBalance(fixture.balanceId(), "10.000", "0.000");
        assertBalance(secondBalance, "5.000", "0.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.consumed);
    }

    @Test
    void consumeProcessesEveryAllocationAndRecordsConsumedQuantities() {
        Fixture fixture = createFixture("10.000", "2.000");
        UUID secondBalance = createLocatedBalance(fixture, "8.000", "3.000");
        InventoryReservation reservation = persistReservation(
                fixture,
                "5.000",
                allocations(
                        allocation(fixture.balanceId(), "2.000", "0.000"),
                        allocation(secondBalance, "3.000", "0.000")));

        lifecycleService.consume(fixture.tenantId(), reservation.getId());

        assertBalance(fixture.balanceId(), "8.000", "0.000");
        assertBalance(secondBalance, "5.000", "0.000");
        JsonNode stored = jsonMapper.readTree(reservations.findById(reservation.getId()).orElseThrow().getAllocations());
        assertThat(stored).hasSize(2);
        Map<UUID, BigDecimal> consumed = new HashMap<>();
        stored.forEach(node -> consumed.put(
                UUID.fromString(node.get("balanceId").asText()),
                node.get("consumedQuantity").decimalValue()));
        assertThat(consumed.get(fixture.balanceId())).isEqualByComparingTo("2.000");
        assertThat(consumed.get(secondBalance)).isEqualByComparingTo("3.000");
        assertThat(consumed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("5.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.consumed);
    }

    @Test
    void releaseProcessesTheUnconsumedRemainderOfEveryAllocation() {
        Fixture fixture = createFixture("10.000", "3.000");
        UUID secondBalance = createLocatedBalance(fixture, "8.000", "4.000");
        InventoryReservation reservation = persistReservation(
                fixture,
                "7.000",
                allocations(
                        allocation(fixture.balanceId(), "3.000", "1.000"),
                        allocation(secondBalance, "4.000", "2.000")));

        lifecycleService.release(fixture.tenantId(), reservation.getId());

        assertBalance(fixture.balanceId(), "10.000", "1.000");
        assertBalance(secondBalance, "8.000", "2.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.released);
    }

    @Test
    void releasedReservationCannotBeConsumed() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));
        lifecycleService.release(fixture.tenantId(), reservation.getId());

        assertThatThrownBy(() -> lifecycleService.consume(fixture.tenantId(), reservation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVALID_INVENTORY_RESERVATION_STATE");
        assertBalance(fixture, "5.000", "0.000");
    }

    @Test
    void consumedReservationCannotBeReleased() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));
        lifecycleService.consume(fixture.tenantId(), reservation.getId());

        assertThatThrownBy(() -> lifecycleService.release(fixture.tenantId(), reservation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVALID_INVENTORY_RESERVATION_STATE");
        assertBalance(fixture, "3.000", "0.000");
    }

    @Test
    void consumeRejectsAnInconsistentReservedQuantityWithoutChangingState() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));
        jdbcTemplate.update(
                "UPDATE inventory_balances SET reserved_quantity = 1.000 WHERE id = ?",
                fixture.balanceId());

        assertThatThrownBy(() -> lifecycleService.consume(fixture.tenantId(), reservation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVENTORY_RESERVATION_INCONSISTENT");
        assertBalance(fixture, "5.000", "1.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.active);
    }

    @Test
    void reservationCannotBeMutatedThroughAnotherTenant() {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));

        assertThatThrownBy(() -> lifecycleService.consume(UUID.randomUUID(), reservation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("INVENTORY_RESERVATION_NOT_FOUND");
        assertBalance(fixture, "5.000", "2.000");
        assertStatus(reservation.getId(), InventoryReservationStatus.active);
    }

    @Test
    void concurrentConsumeIsIdempotentAndDiscountsInventoryOnce() throws Exception {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> transition(
                    fixture.tenantId(), reservation.getId(), true, ready, start));
            Future<Outcome> second = executor.submit(() -> transition(
                    fixture.tenantId(), reservation.getId(), true, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            first.get(20, TimeUnit.SECONDS),
                            second.get(20, TimeUnit.SECONDS)))
                    .allMatch(Outcome::succeeded);
            assertBalance(fixture, "3.000", "0.000");
            assertStatus(reservation.getId(), InventoryReservationStatus.consumed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentConsumeAndReleaseAllowOnlyOneTransition() throws Exception {
        Fixture fixture = createFixture("5.000", "0.000");
        InventoryReservation reservation = lifecycleService.reserve(command(fixture, "2.000"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> consume = executor.submit(() -> transition(
                    fixture.tenantId(), reservation.getId(), true, ready, start));
            Future<Outcome> release = executor.submit(() -> transition(
                    fixture.tenantId(), reservation.getId(), false, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = List.of(
                    consume.get(20, TimeUnit.SECONDS), release.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(outcome -> !outcome.succeeded())
                    .singleElement()
                    .extracting(Outcome::errorCode)
                    .isEqualTo("INVALID_INVENTORY_RESERVATION_STATE");

            assertThat(reservedQuantity(fixture)).isEqualByComparingTo("0.000");
            InventoryReservationStatus status = reservationStatus(reservation.getId());
            BigDecimal expectedPhysical = status == InventoryReservationStatus.consumed
                    ? new BigDecimal("3.000")
                    : new BigDecimal("5.000");
            assertThat(physicalQuantity(fixture)).isEqualByComparingTo(expectedPhysical);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void criticalRepositoriesDeclarePessimisticWriteLocks() throws NoSuchMethodException {
        Lock reservationLock = com.omniretail.backend.ecommerce.repository.InventoryReservationRepository.class
                .getMethod("findByTenantIdAndId", UUID.class, UUID.class)
                .getAnnotation(Lock.class);
        Lock balanceLock = com.omniretail.backend.inventory.repository.InventoryBalanceRepository.class
                .getMethod(
                        "findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull",
                        UUID.class,
                        UUID.class,
                        UUID.class)
                .getAnnotation(Lock.class);

        assertThat(reservationLock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(balanceLock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private ReserveInventoryCommand command(Fixture fixture, String quantity) {
        return new ReserveInventoryCommand(
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                InventoryReservationSourceType.transfer,
                fixture.sourceId(),
                fixture.sourceLineId(),
                null,
                null,
                new BigDecimal(quantity));
    }

    private Outcome transition(
            UUID tenantId,
            UUID reservationId,
            boolean consume,
            CountDownLatch ready,
            CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las transiciones no iniciaron a tiempo.");
        }
        try {
            if (consume) {
                lifecycleService.consume(tenantId, reservationId);
            } else {
                lifecycleService.release(tenantId, reservationId);
            }
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private void assertBalance(Fixture fixture, String physical, String reserved) {
        assertBalance(fixture.balanceId(), physical, reserved);
    }

    private void assertBalance(UUID balanceId, String physical, String reserved) {
        MapBalance balance = jdbcTemplate.queryForObject(
                "SELECT quantity, reserved_quantity FROM inventory_balances WHERE id = ?",
                (resultSet, rowNumber) -> new MapBalance(
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("reserved_quantity")),
                balanceId);
        assertThat(balance.quantity()).isEqualByComparingTo(physical);
        assertThat(balance.reservedQuantity()).isEqualByComparingTo(reserved);
    }

    private void assertStatus(UUID reservationId, InventoryReservationStatus status) {
        assertThat(reservationStatus(reservationId)).isEqualTo(status);
    }

    private InventoryReservationStatus reservationStatus(UUID reservationId) {
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?",
                String.class,
                reservationId);
        return InventoryReservationStatus.valueOf(storedStatus);
    }

    private BigDecimal physicalQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class,
                fixture.balanceId());
    }

    private BigDecimal reservedQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class,
                fixture.balanceId());
    }

    private Fixture createFixture(String quantity, String reservedQuantity) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO branches (id, tenant_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, 'main', 'active')",
                branchId,
                tenantId,
                "MAIN-" + suffix,
                "Principal " + suffix);
        jdbcTemplate.update(
                "INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId,
                tenantId,
                "Categoria " + suffix,
                "categoria-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) "
                        + "VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')",
                unitId,
                tenantId,
                "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
        jdbcTemplate.update(
                "INSERT INTO inventory_balances "
                        + "(id, tenant_id, branch_id, product_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                balanceId,
                tenantId,
                branchId,
                productId,
                new BigDecimal(quantity),
                new BigDecimal(reservedQuantity));
        return new Fixture(
                tenantId,
                branchId,
                productId,
                balanceId,
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    private UUID createLocatedBalance(Fixture fixture, String quantity, String reservedQuantity) {
        UUID locationId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        String suffix = balanceId.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')",
                locationId,
                fixture.tenantId(),
                fixture.branchId(),
                "LOC-" + suffix,
                "Ubicacion " + suffix);
        jdbcTemplate.update(
                "INSERT INTO inventory_balances "
                        + "(id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                balanceId,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                locationId,
                new BigDecimal(quantity),
                new BigDecimal(reservedQuantity));
        return balanceId;
    }

    private InventoryReservation persistReservation(Fixture fixture, String quantity, String allocations) {
        InventoryReservation reservation = InventoryReservation.builder()
                .branchId(fixture.branchId())
                .sourceType(InventoryReservationSourceType.transfer)
                .sourceId(fixture.sourceId())
                .sourceLineId(fixture.sourceLineId())
                .productId(fixture.productId())
                .quantity(new BigDecimal(quantity))
                .allocations(allocations)
                .build();
        reservation.setTenantId(fixture.tenantId());
        return reservations.saveAndFlush(reservation);
    }

    private String allocations(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private String allocation(UUID balanceId, String reserved, String consumed) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + balanceId
                + "\",\"locationId\":null,\"reservedQuantity\":" + reserved
                + ",\"consumedQuantity\":" + consumed + "}";
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID balanceId,
            UUID sourceId,
            UUID sourceLineId) {}

    private record MapBalance(BigDecimal quantity, BigDecimal reservedQuantity) {}

    private record Outcome(boolean succeeded, String errorCode) {}
}
