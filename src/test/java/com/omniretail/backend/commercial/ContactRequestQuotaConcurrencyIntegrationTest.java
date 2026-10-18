package com.omniretail.backend.commercial;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.shared.notification.PlatformEmailSender;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Cuotas bajo carreras reales: PostgreSQL 16 temporal, conexiones y transacciones independientes. */
@SpringBootTest(properties = {
    "app.commercial.contact-requests.enabled=true",
    "app.commercial.contact-requests.team-notification-to=equipo@marjym.test",
    "app.commercial.contact-requests.ip-hash-secret=prueba-secreto-solo-para-tests-0123456789",
    "app.commercial.contact-requests.per-ip-per-day=3",
    "app.commercial.contact-requests.per-email-per-day=3",
    "app.commercial.contact-requests.global-per-day=4",
    "app.commercial.contact-requests.mail-retry-interval=PT1H"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ContactRequestQuotaConcurrencyIntegrationTest {

    private static final int CONTENDERS = 6;
    private static final int QUOTA_LOCK_CLASS = 1296126538; // ASCII "MARJ"
    private static final int QUOTA_LOCK_OBJECT = 1498239316; // ASCII "YMQT"
    private static final String QUOTA_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(" + QUOTA_LOCK_CLASS + ", " + QUOTA_LOCK_OBJECT + ")";

    @Autowired private ContactRequestService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @MockitoBean private PlatformEmailSender emailSender;

    @BeforeEach
    void clearRequests() {
        jdbc.update("DELETE FROM contact_requests");
    }

    @Test
    void concurrentDifferentBusinessesCannotExceedTheEmailQuota() throws Exception {
        String email = ContactRequestPayloads.uniqueEmail();

        List<Attempt> attempts = race(
                i -> request(email, "Negocio correo " + i),
                i -> "198.51.100." + (i + 1));

        assertQuota(attempts, 3, "CONTACT_QUOTA_EXCEEDED");
        assertThat(count("SELECT COUNT(*) FROM contact_requests WHERE email = ?", email)).isEqualTo(3);
    }

    @Test
    void concurrentDifferentEmailsCannotExceedTheIpQuota() throws Exception {
        String sharedIp = "203.0.113.40";

        List<Attempt> attempts = race(
                i -> request("ip-race-" + i + "-" + UUID.randomUUID() + "@example.test", "Negocio IP " + i),
                i -> sharedIp);

        assertQuota(attempts, 3, "CONTACT_QUOTA_EXCEEDED");
        assertThat(count("SELECT COUNT(*) FROM contact_requests")).isEqualTo(3);
    }

    @Test
    void concurrentDistinctEmailsAndIpsCannotExceedTheGlobalQuota() throws Exception {
        List<Attempt> attempts = race(
                i -> request("global-race-" + i + "-" + UUID.randomUUID() + "@example.test", "Negocio global " + i),
                i -> "192.0.2." + (i + 1));

        assertQuota(attempts, 4, "CONTACT_REQUESTS_UNAVAILABLE");
        assertThat(count("SELECT COUNT(*) FROM contact_requests WHERE status = 'RECEIVED'")).isEqualTo(4);
    }

    private List<Attempt> race(
            IntFunction<CreateContactRequest> requests, IntFunction<String> clientIps) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute(QUOTA_LOCK_SQL);
            }
            List<Callable<Attempt>> calls = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                int contender = i;
                calls.add(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("No se libero la barrera de inicio");
                    }
                    try {
                        ContactRequestAccepted accepted =
                                service.submit(requests.apply(contender), UUID.randomUUID(), clientIps.apply(contender));
                        return new Attempt(accepted.requestId(), null);
                    } catch (com.omniretail.backend.shared.exception.BusinessException error) {
                        return new Attempt(null, error.getCode());
                    }
                });
            }
            List<Future<Attempt>> futures = calls.stream().map(pool::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            awaitQuotaLockWaiters();
            // Sin el lock dentro de ContactRequestService, la version vulnerable ya habria insertado aqui.
            assertThat(count("SELECT COUNT(*) FROM contact_requests")).isZero();
            blocker.commit();
            List<Attempt> results = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitQuotaLockWaiters() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Long waiters = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM pg_locks
                    WHERE locktype = 'advisory'
                      AND classid = ?
                      AND objid = ?
                      AND NOT granted
                    """,
                    Long.class,
                    QUOTA_LOCK_CLASS,
                    QUOTA_LOCK_OBJECT);
            if (waiters != null && waiters == CONTENDERS) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Las solicitudes no esperaron el advisory lock transaccional");
    }

    private static void assertQuota(List<Attempt> attempts, int accepted, String rejectionCode) {
        assertThat(attempts).filteredOn(attempt -> attempt.requestId() != null).hasSize(accepted);
        assertThat(attempts).filteredOn(attempt -> rejectionCode.equals(attempt.errorCode()))
                .hasSize(CONTENDERS - accepted);
    }

    private static CreateContactRequest request(String email, String businessName) {
        return new CreateContactRequest(
                "Ana Perez",
                businessName,
                email,
                "+502 5555-1234",
                "retail",
                List.of("inventory", "pos"),
                "Solicitud concurrente",
                true,
                "2026-10",
                "",
                null);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private record Attempt(UUID requestId, String errorCode) {
    }
}
