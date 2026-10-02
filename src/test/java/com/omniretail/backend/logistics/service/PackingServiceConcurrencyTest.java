package com.omniretail.backend.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.logistics.dto.PackingActionResponse;
import com.omniretail.backend.logistics.dto.PackingChecklistRequest;
import com.omniretail.backend.logistics.dto.SavePackingPreparationRequest;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
class PackingServiceConcurrencyTest {

    @Autowired private PackingService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @Test
    void concurrentRetryWithSameOperationIdAppliesMutationOnce() throws Exception {
        Fixture fixture = fixture();
        actor(fixture);

        List<PackingActionResponse> results = concurrentPreparation(fixture, "same-operation");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PackingActionResponse::idempotent)
                .containsExactlyInAnyOrder(false, true);
        assertThat(results).allSatisfy(result -> assertThat(result.packing().version()).isEqualTo(1L));
        assertThat(operationCount(fixture)).isOne();
    }

    @Test
    void concurrentMutationsWithSameVersionAllowOnlyOneWinner() throws Exception {
        Fixture fixture = fixture();
        actor(fixture);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PackingActionResponse> first = executor.submit(
                    () -> prepare(fixture, "operation-a", ready, start));
            Future<PackingActionResponse> second = executor.submit(
                    () -> prepare(fixture, "operation-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            int versionConflicts = 0;
            for (Future<PackingActionResponse> future : List.of(first, second)) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof BusinessException business
                            && business.getCode().equals("PACKING_VERSION_CONFLICT")) {
                        versionConflicts++;
                    } else {
                        throw exception;
                    }
                }
            }
            assertThat(successes).isOne();
            assertThat(versionConflicts).isOne();
            assertThat(operationCount(fixture)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private List<PackingActionResponse> concurrentPreparation(Fixture fixture, String operationId)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PackingActionResponse> first = executor.submit(
                    () -> prepare(fixture, operationId, ready, start));
            Future<PackingActionResponse> second = executor.submit(
                    () -> prepare(fixture, operationId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private PackingActionResponse prepare(
            Fixture fixture,
            String operationId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las mutaciones de Packing no iniciaron a tiempo.");
        }
        return service.savePreparation(
                fixture.branchId(),
                fixture.packingId(),
                new SavePackingPreparationRequest(
                        0L,
                        operationId,
                        new PackingChecklistRequest(true, true, true),
                        new BigDecimal("2.000"),
                        1));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID picking = UUID.randomUUID();
        UUID packing = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Packing concurrency', ?)",
                tenant, "packing-concurrency-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "BR-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Empacador', ?, 'employee', 'active', ?)
                """, user, tenant, user + "@test.local", branch);
        jdbc.update("""
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                     total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{"name":"Cliente"}'::jsonb, 'packing',
                        'home_delivery', 'third_party', 10, 0, 0, 10, ?)
                """, order, tenant, branch, "WEB-" + order, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, order_id, status,
                     priority, completed_at)
                VALUES (?, ?, ?, 'order', ?, ?, 'completed', 'normal', now())
                """, picking, tenant, branch, order, order);
        jdbc.update("""
                INSERT INTO packings
                    (id, tenant_id, branch_id, source_type, source_id, order_id, picking_order_id,
                     started_by_user_id, started_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, now())
                """, packing, tenant, branch, order, order, picking, user);
        return new Fixture(tenant, branch, user, packing);
    }

    private void actor(Fixture fixture) {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.userId(), fixture.tenantId(), UserType.employee,
                null, fixture.branchId(), UUID.randomUUID()));
    }

    private long operationCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM packing_operations WHERE tenant_id = ?",
                Long.class, fixture.tenantId());
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID userId, UUID packingId) {}
}
