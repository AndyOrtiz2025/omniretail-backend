package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
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
class InventorySettingsConcurrencyTest {

    @Autowired private InventorySettingsService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @Test
    void concurrentInitialUpsertsCreateExactlyOneRow() throws Exception {
        Fixture fixture = fixture();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), fixture.tenant(), UserType.employee,
                UUID.randomUUID(), fixture.branch(), UUID.randomUUID()));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BigDecimal> first = executor.submit(() -> upsert(fixture, "3", ready, start));
            Future<BigDecimal> second = executor.submit(() -> upsert(fixture, "7", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<BigDecimal> submitted = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(rowCount(fixture)).isOne();
            BigDecimal stored = jdbc.queryForObject("""
                    SELECT min_stock FROM product_inventory_settings
                    WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                    """, BigDecimal.class, fixture.tenant(), fixture.branch(), fixture.product());
            assertThat(submitted).anySatisfy(value -> assertThat(stored).isEqualByComparingTo(value));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private BigDecimal upsert(
            Fixture fixture, String value, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Los upserts no iniciaron a tiempo.");
        }
        service.upsert(
                fixture.branch(),
                fixture.product(),
                new UpdateInventorySettingsRequest(new BigDecimal(value), null, null));
        return new BigDecimal(value);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Settings race', ?)", tenant, "race-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     status, tracking_stock)
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, 'published', true)
                """, product, tenant, "SKU-" + product, category, unit);
        return new Fixture(tenant, branch, product);
    }

    private long rowCount(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM product_inventory_settings
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                """, Long.class, fixture.tenant(), fixture.branch(), fixture.product());
    }

    private record Fixture(UUID tenant, UUID branch, UUID product) {}
}
