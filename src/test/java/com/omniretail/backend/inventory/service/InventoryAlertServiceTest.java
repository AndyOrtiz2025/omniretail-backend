package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.InventoryAlertResponse;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryAlertServiceTest {

    @Autowired private InventoryAlertService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccessResolver;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void productWithoutBalanceOrSettingsIsOutOfStock() {
        Fixture fixture = fixture();
        UUID product = addProduct(fixture, "physical", true, "published", "Sin balance");

        InventoryAlertResponse alert = service.list(
                        fixture.branch(), null, PageRequest.of(0, 20))
                .items()
                .getFirst();

        assertThat(alert.productId()).isEqualTo(product);
        assertThat(alert.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(alert.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(alert.availableQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(alert.minStock()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(alert.reorderPoint()).isNull();
        assertThat(alert.status()).isEqualTo(InventoryAlertStatus.out_of_stock);
        assertThat(alert.suggestedReorder()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aggregatesNullAndLocatedBalancesUsingAvailableQuantityAndReorderPoint() {
        Fixture fixture = fixture();
        UUID product = addProduct(fixture, "physical", true, "published", "Agregado");
        UUID location = addLocation(fixture);
        addSettings(fixture, product, "10", "20");
        addBalance(fixture, product, null, "8", "2");
        addBalance(fixture, product, location, "3", "1");

        InventoryAlertResponse alert = service.list(
                        fixture.branch(), InventoryAlertStatus.critical, PageRequest.of(0, 20))
                .items()
                .getFirst();

        assertThat(alert.quantity()).isEqualByComparingTo("11");
        assertThat(alert.reservedQuantity()).isEqualByComparingTo("3");
        assertThat(alert.availableQuantity()).isEqualByComparingTo("8");
        assertThat(alert.status()).isEqualTo(InventoryAlertStatus.critical);
        assertThat(alert.suggestedReorder()).isEqualByComparingTo("12");
    }

    @Test
    void appliesExactStatusBoundariesFallbackTargetAndNonNegativeSuggestion() {
        Fixture fixture = fixture();
        UUID out = addProduct(fixture, "physical", true, "published", "Out");
        addSettings(fixture, out, "10", null);
        addBalance(fixture, out, null, "0", "0");

        UUID critical = addProduct(fixture, "physical", true, "published", "Critical");
        addSettings(fixture, critical, "0.300", null);
        addBalance(fixture, critical, null, "0.299", "0");

        UUID near = addProduct(fixture, "physical", true, "published", "Near");
        addSettings(fixture, near, "10", "5");
        addBalance(fixture, near, null, "12.500", "0");

        UUID normal = addProduct(fixture, "physical", true, "published", "Normal");
        addSettings(fixture, normal, "10", null);
        addBalance(fixture, normal, null, "12.501", "0");

        PageResponse<InventoryAlertResponse> result = service.list(
                fixture.branch(), null, PageRequest.of(0, 20));
        assertThat(result.items()).extracting(InventoryAlertResponse::productId)
                .containsExactlyInAnyOrder(out, critical, near)
                .doesNotContain(normal);
        InventoryAlertResponse criticalAlert = result.items().stream()
                .filter(item -> item.productId().equals(critical))
                .findFirst()
                .orElseThrow();
        assertThat(criticalAlert.status()).isEqualTo(InventoryAlertStatus.critical);
        assertThat(criticalAlert.suggestedReorder()).isEqualByComparingTo("0.001");
        InventoryAlertResponse nearAlert = result.items().stream()
                .filter(item -> item.productId().equals(near))
                .findFirst()
                .orElseThrow();
        assertThat(nearAlert.status()).isEqualTo(InventoryAlertStatus.near_minimum);
        assertThat(nearAlert.suggestedReorder()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void statusFilteringAndPaginationAreAppliedBeforePageSlicing() {
        Fixture fixture = fixture();
        for (int index = 0; index < 3; index++) {
            addProduct(fixture, "physical", true, "published", "Out " + index);
        }
        UUID critical = addProduct(fixture, "physical", true, "published", "Critical");
        addSettings(fixture, critical, "5", null);
        addBalance(fixture, critical, null, "2", "0");

        PageResponse<InventoryAlertResponse> secondPage = service.list(
                fixture.branch(), InventoryAlertStatus.out_of_stock, PageRequest.of(1, 1));
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items()).allMatch(item ->
                item.status() == InventoryAlertStatus.out_of_stock);
        assertThat(secondPage.totalItems()).isEqualTo(3);
        assertThat(secondPage.totalPages()).isEqualTo(3);
    }

    @Test
    void excludesIneligibleAndUnpublishedProductsAndPositiveNoSettingsProduct() {
        Fixture fixture = fixture();
        addProduct(fixture, "physical", false, "published", "No tracking");
        addProduct(fixture, "service", true, "published", "Servicio");
        addProduct(fixture, "kit", true, "published", "Kit");
        addProduct(fixture, "physical", true, "archived", "Archivado");
        UUID positiveNoSettings = addProduct(
                fixture, "physical", true, "published", "Normal sin settings");
        addBalance(fixture, positiveNoSettings, null, "1", "0");

        assertThat(service.list(fixture.branch(), null, PageRequest.of(0, 20)).items())
                .isEmpty();
    }

    @Test
    void alertQueryIsTenantIsolated() {
        Fixture tenantA = fixture();
        UUID expected = addProduct(tenantA, "physical", true, "published", "Tenant A");
        Fixture tenantB = fixture();
        addProduct(tenantB, "physical", true, "published", "Tenant B");
        useActor(tenantA);

        assertThat(service.list(tenantA.branch(), null, PageRequest.of(0, 20)).items())
                .extracting(InventoryAlertResponse::productId)
                .containsExactly(expected);
    }

    @Test
    void normalFilterIsRejectedAndBranchCapabilityAndTenantAreEnforced() {
        Fixture fixture = fixture();
        addProduct(fixture, "physical", true, "published", "Producto");
        assertThatThrownBy(() -> service.list(
                        fixture.branch(), InventoryAlertStatus.normal, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVENTORY_ALERT_STATUS_INVALID"));

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.list(fixture.branch(), null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_ACCESS_DENIED"));

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.pos)));
        assertThatThrownBy(() -> service.list(fixture.branch(), null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAPABILITY_REQUIRED"));

        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        Fixture other = fixture();
        useActor(fixture);
        assertThatThrownBy(() -> service.list(other.branch(), null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_NOT_FOUND"));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Alert test', ?)", tenant, "alert-" + tenant);
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
        Fixture fixture = new Fixture(tenant, branch, unit, category, user);
        useActor(fixture);
        return fixture;
    }

    private void useActor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee,
                UUID.randomUUID(), fixture.branch(), UUID.randomUUID()));
    }

    private UUID addProduct(
            Fixture fixture, String type, boolean trackingStock, String status, String name) {
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     status, tracking_stock)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, product, fixture.tenant(), "SKU-" + product, name, type,
                fixture.category(), fixture.unit(), status, trackingStock);
        return product;
    }

    private UUID addLocation(Fixture fixture) {
        UUID location = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Ubicación', 'warehouse', 'active')
                """, location, fixture.tenant(), fixture.branch(),
                "L-" + location.toString().substring(0, 8));
        return location;
    }

    private void addSettings(Fixture fixture, UUID product, String minStock, String reorderPoint) {
        jdbc.update("""
                INSERT INTO product_inventory_settings
                    (tenant_id, branch_id, product_id, min_stock, reorder_point)
                VALUES (?, ?, ?, ?::numeric, ?::numeric)
                """, fixture.tenant(), fixture.branch(), product, minStock, reorderPoint);
    }

    private void addBalance(
            Fixture fixture, UUID product, UUID location, String quantity, String reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances
                    (tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?::numeric, ?::numeric)
                """, fixture.tenant(), fixture.branch(), product, location, quantity, reserved);
    }

    private record Fixture(UUID tenant, UUID branch, UUID unit, UUID category, UUID user) {}
}
