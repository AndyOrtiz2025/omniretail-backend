package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
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
class ProductUniqueConcurrencyTest {

    @Autowired private ProductService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BusinessConfigService businessConfigService;
    @MockitoBean private TenantCapabilityGuard tenantCapabilityGuard;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), fixture.tenant(), UserType.employee,
                null, null, UUID.randomUUID()));
        given(businessConfigService.getConfig()).willReturn(new BusinessConfigResponse(
                fixture.tenant(), BusinessPreset.custom,
                true, true, true, true, true, true, true, true, true,
                List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(
                        true, true, true, true)));
    }

    @Test
    void concurrentSkuCollisionProducesOneProductAndStableConflict() throws Exception {
        String sku = "RACE-SKU-" + UUID.randomUUID();
        List<Outcome> outcomes = race(
                request(sku, "BAR-A-" + UUID.randomUUID()),
                request(sku, "BAR-B-" + UUID.randomUUID()));

        assertStableRace(outcomes, "PRODUCT_SKU_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM products WHERE tenant_id = ? AND sku = ?",
                        Long.class,
                        fixture.tenant(),
                        sku.toUpperCase(java.util.Locale.ROOT)))
                .isOne();
    }

    @Test
    void concurrentBarcodeCollisionProducesOneProductAndStableConflict() throws Exception {
        String barcode = "RACE-BAR-" + UUID.randomUUID();
        List<Outcome> outcomes = race(
                request("SKU-A-" + UUID.randomUUID(), barcode),
                request("SKU-B-" + UUID.randomUUID(), barcode));

        assertStableRace(outcomes, "PRODUCT_BARCODE_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM products WHERE tenant_id = ? AND barcode = ?",
                        Long.class,
                        fixture.tenant(),
                        barcode))
                .isOne();
    }

    private List<Outcome> race(ProductCreateRequest firstRequest, ProductCreateRequest secondRequest)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(() -> create(firstRequest, ready, start));
            Future<Outcome> second = executor.submit(() -> create(secondRequest, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Outcome create(
            ProductCreateRequest request, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Las altas concurrentes no iniciaron a tiempo.");
        }
        try {
            service.create(request);
            return new Outcome(true, null);
        } catch (BusinessException exception) {
            return new Outcome(false, exception.getCode());
        }
    }

    private static void assertStableRace(List<Outcome> outcomes, String expectedCode) {
        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.success())
                .singleElement()
                .extracting(Outcome::code)
                .isEqualTo(expectedCode);
    }

    private ProductCreateRequest request(String sku, String barcode) {
        return new ProductCreateRequest(
                sku,
                barcode,
                "Producto carrera",
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
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Product race', ?)",
                tenant, "product-race-" + tenant);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        return new Fixture(tenant, category, unit);
    }

    private record Fixture(UUID tenant, UUID category, UUID unit) {}

    private record Outcome(boolean success, String code) {}
}
