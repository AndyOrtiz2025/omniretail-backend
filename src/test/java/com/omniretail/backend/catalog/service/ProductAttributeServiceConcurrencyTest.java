package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductAttributeValueRequest;
import com.omniretail.backend.catalog.dto.ReplaceProductAttributesRequest;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.Map;
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
class ProductAttributeServiceConcurrencyTest {

    @Autowired private ProductAttributeService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BusinessConfigService businessConfigService;

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
    void concurrentBulkReplacementsNeverLeaveAMixedState() throws Exception {
        ReplaceProductAttributesRequest firstRequest = request("red", "1");
        ReplaceProductAttributesRequest secondRequest = request("blue", "2");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> replace(firstRequest, ready, start));
            Future<?> second = executor.submit(() -> replace(secondRequest, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT ad.code, pav.value_string
                    FROM product_attribute_values pav
                    JOIN attribute_definitions ad ON ad.id = pav.attribute_definition_id
                    WHERE pav.tenant_id = ? AND pav.product_id = ?
                    ORDER BY ad.code
                    """, fixture.tenant(), fixture.product());
            assertThat(rows).hasSize(2);
            Map<String, String> state = Map.of(
                    rows.get(0).get("code").toString(), rows.get(0).get("value_string").toString(),
                    rows.get(1).get("code").toString(), rows.get(1).get("value_string").toString());
            assertThat(state).isIn(
                    Map.of("color", "red", "size", "1"),
                    Map.of("color", "blue", "size", "2"));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Void replace(
            ReplaceProductAttributesRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Los reemplazos no iniciaron a tiempo.");
        }
        service.replace(fixture.product(), request);
        return null;
    }

    private ReplaceProductAttributesRequest request(String color, String size) {
        return new ReplaceProductAttributesRequest(List.of(
                new ProductAttributeValueRequest(fixture.color(), color),
                new ProductAttributeValueRequest(fixture.size(), size)));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID color = UUID.randomUUID();
        UUID size = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Attribute race', ?)",
                tenant, "attribute-race-" + tenant);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     sale_price, status, tracking_stock)
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, 10.00, 'published', true)
                """, product, tenant, "SKU-" + product, category, unit);
        jdbc.update("""
                INSERT INTO attribute_definitions (id, tenant_id, code, name, data_type, status)
                VALUES (?, ?, 'color', 'Color', 'TEXT', 'active'),
                       (?, ?, 'size', 'Size', 'NUMBER', 'active')
                """, color, tenant, size, tenant);
        return new Fixture(tenant, product, color, size);
    }

    private record Fixture(UUID tenant, UUID product, UUID color, UUID size) {}
}
