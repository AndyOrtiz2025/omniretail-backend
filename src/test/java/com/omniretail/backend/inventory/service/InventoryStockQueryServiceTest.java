package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class InventoryStockQueryServiceTest {

    @Autowired private InventoryStockQueryService service;
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
    void returnsAllStatusesAndSummaryUsesTheCompleteFilteredDataset() {
        Fixture fixture = fixture();
        addProduct(fixture, fixture.category(), "Sin stock", null, null, null);
        addProduct(fixture, fixture.category(), "Critico", "5", "0", "10");
        addProduct(fixture, fixture.category(), "Cerca", "11", "0", "10");
        addProduct(fixture, fixture.category(), "Normal", "20", "0", "10");

        var response = service.list(
                fixture.branch(), null, null, null, PageRequest.of(0, 1));

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalItems()).isEqualTo(4);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.summary().activeProducts()).isEqualTo(4);
        assertThat(response.summary().lowStock()).isEqualTo(2);
        assertThat(response.summary().outOfStock()).isEqualTo(1);

        var critical = service.list(
                fixture.branch(), null, null, InventoryAlertStatus.critical, PageRequest.of(0, 20));
        assertThat(critical.items()).hasSize(1);
        assertThat(critical.items().getFirst().status()).isEqualTo(InventoryAlertStatus.critical);
        assertThat(critical.summary().activeProducts()).isEqualTo(1);
        assertThat(critical.summary().lowStock()).isEqualTo(1);
        assertThat(critical.summary().outOfStock()).isZero();
    }

    @Test
    void searchAndCategoryFiltersRunBeforePaginationAndAffectSummary() {
        Fixture fixture = fixture();
        UUID location = addLocation(fixture, "Bodega Norte");
        UUID expected = addProduct(
                fixture, fixture.category(), "Taladro industrial", "8", "1", "10", location);
        UUID otherCategory = addCategory(fixture, "Otra categoria");
        addProduct(fixture, otherCategory, "Martillo", "20", "0", "5");

        var byLocation = service.list(
                fixture.branch(), "bodega norte", null, null, PageRequest.of(0, 1));
        assertThat(byLocation.items()).extracting(item -> item.productId()).containsExactly(expected);
        assertThat(byLocation.items().getFirst().categoryName()).isEqualTo("Categoria stock");
        assertThat(byLocation.items().getFirst().defaultLocationName()).isEqualTo("Bodega Norte");
        assertThat(byLocation.totalItems()).isEqualTo(1);
        assertThat(byLocation.summary().activeProducts()).isEqualTo(1);

        var byCategory = service.list(
                fixture.branch(), null, otherCategory, null, PageRequest.of(0, 20));
        assertThat(byCategory.items()).hasSize(1);
        assertThat(byCategory.items().getFirst().categoryId()).isEqualTo(otherCategory);
        assertThat(byCategory.summary().activeProducts()).isEqualTo(1);
    }

    @Test
    void tenantAndBranchAccessAreEnforced() {
        Fixture tenantA = fixture();
        UUID expected = addProduct(tenantA, tenantA.category(), "Tenant A", null, null, null);
        Fixture tenantB = fixture();
        addProduct(tenantB, tenantB.category(), "Tenant B", null, null, null);
        useActor(tenantA);

        assertThat(service.list(tenantA.branch(), null, null, null, PageRequest.of(0, 20)).items())
                .extracting(item -> item.productId())
                .containsExactly(expected);

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.list(
                        tenantA.branch(), null, null, null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_ACCESS_DENIED"));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = addTenantBranchUnitAndCategory(tenant, branch, unit);
        Fixture fixture = new Fixture(tenant, branch, unit, category, UUID.randomUUID());
        useActor(fixture);
        return fixture;
    }

    private UUID addTenantBranchUnitAndCategory(UUID tenant, UUID branch, UUID unit) {
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Stock test', ?)", tenant, "stock-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        UUID category = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Categoria stock', ?, 'active')
                """, category, tenant, "stock-category-" + category);
        return category;
    }

    private UUID addCategory(Fixture fixture, String name) {
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, ?, ?)",
                category, fixture.tenant(), name, "category-" + category);
        return category;
    }

    private UUID addLocation(Fixture fixture, String name) {
        UUID location = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')
                """, location, fixture.tenant(), fixture.branch(),
                "L-" + location.toString().substring(0, 8), name);
        return location;
    }

    private UUID addProduct(
            Fixture fixture, UUID category, String name, String quantity, String reserved, String minStock) {
        return addProduct(fixture, category, name, quantity, reserved, minStock, null);
    }

    private UUID addProduct(
            Fixture fixture,
            UUID category,
            String name,
            String quantity,
            String reserved,
            String minStock,
            UUID defaultLocation) {
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     status, tracking_stock)
                VALUES (?, ?, ?, ?, 'physical', ?, ?, 'published', true)
                """, product, fixture.tenant(), "SKU-" + product, name, category, fixture.unit());
        if (minStock != null) {
            jdbc.update("""
                    INSERT INTO product_inventory_settings
                        (tenant_id, branch_id, product_id, min_stock, reorder_point, default_location_id)
                    VALUES (?, ?, ?, ?::numeric, NULL, ?)
                    """, fixture.tenant(), fixture.branch(), product, minStock, defaultLocation);
        }
        if (quantity != null) {
            jdbc.update("""
                    INSERT INTO inventory_balances
                        (tenant_id, branch_id, product_id, quantity, reserved_quantity)
                    VALUES (?, ?, ?, ?::numeric, ?::numeric)
                    """, fixture.tenant(), fixture.branch(), product, quantity, reserved);
        }
        return product;
    }

    private void useActor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee,
                UUID.randomUUID(), fixture.branch(), UUID.randomUUID()));
    }

    private record Fixture(UUID tenant, UUID branch, UUID unit, UUID category, UUID user) {}
}
