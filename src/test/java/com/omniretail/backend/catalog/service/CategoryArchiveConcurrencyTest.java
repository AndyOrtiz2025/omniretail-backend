package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.CategoryCreateRequest;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.util.List;
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
class CategoryArchiveConcurrencyTest {

    @Autowired private CategoryService categoryService;
    @Autowired private ProductService productService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BusinessConfigService businessConfigService;
    @MockitoBean private TenantCapabilityGuard tenantCapabilityGuard;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee,
                null, null, UUID.randomUUID()));
        given(businessConfigService.getConfig()).willReturn(new BusinessConfigResponse(
                fixture.tenant(), BusinessPreset.custom,
                true, true, true, true, true, true, true, true, true,
                List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(
                        true, true, true, true)));
    }

    @Test
    void assigningAProductAndArchivingItsCategoryCannotBothSucceed() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> create = executor.submit(() -> race(ready, start, () ->
                    productService.create(request())));
            Future<Outcome> archive = executor.submit(() -> race(ready, start, () ->
                    categoryService.archive(fixture.category())));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Outcome createOutcome = create.get(30, TimeUnit.SECONDS);
            Outcome archiveOutcome = archive.get(30, TimeUnit.SECONDS);
            long productCount = jdbc.queryForObject(
                    "SELECT count(*) FROM products WHERE tenant_id = ? AND category_id = ?",
                    Long.class,
                    fixture.tenant(),
                    fixture.category());
            String categoryStatus = jdbc.queryForObject(
                    "SELECT status FROM categories WHERE id = ?",
                    String.class,
                    fixture.category());

            assertThat(createOutcome.success() ^ archiveOutcome.success()).isTrue();
            if (createOutcome.success()) {
                assertThat(archiveOutcome.code()).isEqualTo("CATEGORY_HAS_PRODUCTS");
                assertThat(categoryStatus).isEqualTo("active");
                assertThat(productCount).isOne();
            } else {
                assertThat(createOutcome.code()).isEqualTo("CATEGORY_NOT_FOUND");
                assertThat(archiveOutcome.success()).isTrue();
                assertThat(categoryStatus).isEqualTo("archived");
                assertThat(productCount).isZero();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void creatingAnActiveChildAndArchivingItsParentCannotBothSucceed() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> create = executor.submit(() -> race(ready, start, () ->
                    categoryService.create(new CategoryCreateRequest(
                            fixture.category(),
                            "Hija concurrente",
                            "child-" + UUID.randomUUID(),
                            null,
                            null,
                            CategoryStatus.active))));
            Future<Outcome> archive = executor.submit(() -> race(ready, start, () ->
                    categoryService.archive(fixture.category())));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Outcome createOutcome = create.get(30, TimeUnit.SECONDS);
            Outcome archiveOutcome = archive.get(30, TimeUnit.SECONDS);
            long activeChildren = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM categories
                    WHERE tenant_id = ? AND parent_id = ? AND status = 'active'
                    """,
                    Long.class,
                    fixture.tenant(),
                    fixture.category());
            String parentStatus = jdbc.queryForObject(
                    "SELECT status FROM categories WHERE id = ?",
                    String.class,
                    fixture.category());

            assertThat(createOutcome.success() ^ archiveOutcome.success()).isTrue();
            if (createOutcome.success()) {
                assertThat(archiveOutcome.code()).isEqualTo("CATEGORY_HAS_ACTIVE_CHILDREN");
                assertThat(parentStatus).isEqualTo("active");
                assertThat(activeChildren).isOne();
            } else {
                assertThat(createOutcome.code()).isEqualTo("CATEGORY_PARENT_NOT_FOUND");
                assertThat(archiveOutcome.success()).isTrue();
                assertThat(parentStatus).isEqualTo("archived");
                assertThat(activeChildren).isZero();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Outcome race(CountDownLatch ready, CountDownLatch start, Runnable operation)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las operaciones concurrentes no iniciaron a tiempo.");
        }
        try {
            operation.run();
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private ProductCreateRequest request() {
        return new ProductCreateRequest(
                "RACE-" + UUID.randomUUID(),
                null,
                "Producto concurrente",
                null,
                null,
                ProductType.physical,
                fixture.category(),
                fixture.unit(),
                null,
                null,
                BigDecimal.ONE,
                ProductStatus.published,
                new ProductTrackingDto(false, false, false, false),
                new ProductChannelsDto(true, true, false));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Category race', ?)",
                tenant, "category-race-" + tenant);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status)
                VALUES (?, ?, 'Category race actor', ?, 'employee', 'active')
                """, user, tenant, "category-race-" + user + "@test.local");
        return new Fixture(tenant, category, unit, user);
    }

    private record Fixture(UUID tenant, UUID category, UUID unit, UUID user) {}

    private record Outcome(boolean success, String code) {}
}
