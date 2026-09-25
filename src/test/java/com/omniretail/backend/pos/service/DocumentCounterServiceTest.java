package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.TenantRepository;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DocumentCounterServiceTest {

    private static final String POS_SALE_COUNTER_KEY = "pos_sale";

    @Autowired
    private DocumentCounterService documentCounterService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void returnsFirstAndSecondNumberForTenant() {
        UUID tenantId = createTenant();

        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-001");
        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-002");
    }

    @Test
    void keepsIndependentCountersPerTenant() {
        UUID firstTenantId = createTenant();
        UUID secondTenantId = createTenant();

        assertThat(documentCounterService.nextPosSaleNumber(firstTenantId)).isEqualTo("POS-001");
        assertThat(documentCounterService.nextPosSaleNumber(secondTenantId)).isEqualTo("POS-001");
    }

    @Test
    void appliesMinimumPaddingWithoutTruncatingLargerValues() {
        UUID tenantId = createTenant();
        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-001");

        setLastValue(tenantId, 24L);
        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-025");

        setLastValue(tenantId, 998L);
        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-999");
        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-1000");
    }

    @Test
    void concurrentCallsForSameTenantReturnDistinctSequentialNumbers() throws Exception {
        UUID tenantId = createTenant();
        int callCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callCount);
        CountDownLatch ready = new CountDownLatch(callCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < callCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Las llamadas concurrentes no iniciaron a tiempo.");
                    }
                    return documentCounterService.nextPosSaleNumber(tenantId);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> numbers = new ArrayList<>();
            for (Future<String> future : futures) {
                numbers.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(new HashSet<>(numbers)).hasSize(callCount);
            assertThat(numbers)
                    .containsExactlyInAnyOrder(
                            "POS-001",
                            "POS-002",
                            "POS-003",
                            "POS-004",
                            "POS-005",
                            "POS-006",
                            "POS-007",
                            "POS-008");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void incrementRollsBackWithOuterTransaction() {
        UUID tenantId = createTenant();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    documentCounterService.nextPosSaleNumber(tenantId);
                    throw new ForcedRollbackException();
                }))
                .isInstanceOf(ForcedRollbackException.class);

        assertThat(documentCounterService.nextPosSaleNumber(tenantId)).isEqualTo("POS-001");
    }

    @Test
    void rejectsNullTenantId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> documentCounterService.nextPosSaleNumber(null));
    }

    private UUID createTenant() {
        String uniqueValue = UUID.randomUUID().toString();
        Tenant tenant = Tenant.builder()
                .name("Tenant " + uniqueValue)
                .slug("tenant-" + uniqueValue)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build();
        return tenantRepository.saveAndFlush(tenant).getId();
    }

    private void setLastValue(UUID tenantId, long lastValue) {
        int updated = jdbcTemplate.update(
                """
                UPDATE document_counters
                SET last_value = ?, updated_at = now()
                WHERE tenant_id = ? AND counter_key = ?
                """,
                lastValue,
                tenantId,
                POS_SALE_COUNTER_KEY);
        assertThat(updated).isOne();
    }

    private static final class ForcedRollbackException extends RuntimeException {}
}
