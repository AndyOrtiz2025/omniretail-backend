package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.logistics.dto.ConfirmDispatchRequest;
import com.omniretail.backend.logistics.dto.DispatchResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DispatchServiceConcurrencyTest {

    @Autowired private DispatchService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @Test
    void concurrentDifferentOperationsProduceOneCanonicalDispatchAndOneConsumption() throws Exception {
        DispatchTestFixture.Data fixture = fixture();

        List<Outcome> outcomes = concurrently(fixture, "operation-a", "operation-b");

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::code)
                .isIn("INVALID_ORDER_STATUS_TRANSITION", "DISPATCH_ALREADY_EXISTS");
        assertFinalState(fixture);
    }

    @Test
    void concurrentSameOperationAndPayloadIsIdempotent() throws Exception {
        DispatchTestFixture.Data fixture = fixture();

        List<Outcome> outcomes = concurrently(fixture, "same-operation", "same-operation");

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).extracting(outcome -> outcome.response().idempotent())
                .containsExactlyInAnyOrder(false, true);
        assertThat(outcomes.getFirst().response().dispatchId())
                .isEqualTo(outcomes.getLast().response().dispatchId());
        assertFinalState(fixture);
    }

    private List<Outcome> concurrently(
            DispatchTestFixture.Data fixture, String firstOperation, String secondOperation)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(
                    () -> confirm(fixture, firstOperation, ready, start));
            Future<Outcome> second = executor.submit(
                    () -> confirm(fixture, secondOperation, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome confirm(
            DispatchTestFixture.Data fixture,
            String operationId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Dispatch confirmations did not start in time.");
        }
        try {
            DispatchResponse response = service.confirm(
                    fixture.branchId(), fixture.orderId(), request(operationId));
            return new Outcome(true, null, response);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode(), null);
        }
    }

    private DispatchTestFixture.Data fixture() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        return fixture;
    }

    private ConfirmDispatchRequest request(String operationId) {
        return new ConfirmDispatchRequest(
                operationId,
                "Cargo Express",
                "GUIA-001",
                List.of(
                        new ConfirmDispatchRequest.PackageRequest(
                                "PKG-1", new BigDecimal("2.000"), "Caja 1"),
                        new ConfirmDispatchRequest.PackageRequest(
                                "PKG-2", new BigDecimal("2.500"), "Caja 2")));
    }

    private void assertFinalState(DispatchTestFixture.Data fixture) {
        assertThat(count("dispatches", "order_id", fixture.orderId())).isOne();
        assertThat(count("dispatch_operations", "tenant_id", fixture.tenantId())).isOne();
        UUID dispatchId = jdbc.queryForObject(
                "SELECT id FROM dispatches WHERE order_id = ?", UUID.class, fixture.orderId());
        assertThat(count("inventory_movements", "reference_id", dispatchId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()))
                .isEqualTo("dispatched");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?",
                String.class, fixture.reservationId())).isEqualTo("consumed");
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, fixture.balanceId())).isEqualByComparingTo("5.000");
        assertThat(jdbc.queryForObject(
                "SELECT reserved_quantity FROM inventory_balances WHERE id = ?",
                BigDecimal.class, fixture.balanceId())).isEqualByComparingTo("0.000");
    }

    private long count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    private record Outcome(boolean succeeded, String code, DispatchResponse response) {}
}
