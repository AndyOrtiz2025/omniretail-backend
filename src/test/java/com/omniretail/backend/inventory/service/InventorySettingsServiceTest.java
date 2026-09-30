package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.inventory.dto.ProductInventorySettingsResponse;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
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
class InventorySettingsServiceTest {

    @Autowired private InventorySettingsService service;
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
    void putCreatesAndUpdatesSameRowWithoutTouchingStockOrMovements() {
        Fixture fixture = fixture(true);
        insertBalance(fixture, "8", "2");

        ProductInventorySettingsResponse created = service.upsert(
                fixture.branch(),
                fixture.product(),
                new UpdateInventorySettingsRequest(
                        BigDecimal.ZERO, null, fixture.location()));
        ProductInventorySettingsResponse updated = service.upsert(
                fixture.branch(),
                fixture.product(),
                new UpdateInventorySettingsRequest(
                        new BigDecimal("3.250"), new BigDecimal("7.500"), null));

        assertThat(created.id()).isEqualTo(updated.id());
        assertThat(updated.minStock()).isEqualByComparingTo("3.250");
        assertThat(updated.reorderPoint()).isEqualByComparingTo("7.500");
        assertThat(updated.defaultLocationId()).isNull();
        assertThat(settingsCount(fixture)).isOne();
        assertThat(balanceQuantity(fixture)).isEqualByComparingTo("8");
        assertThat(movementCount(fixture.tenant())).isZero();
    }

    @Test
    void listAndDetailAreTenantBranchAndDatabasePaginated() {
        Fixture fixture = fixture(true);
        assertThat(service.get(fixture.branch(), fixture.product())).isEmpty();
        service.upsert(
                fixture.branch(), fixture.product(),
                new UpdateInventorySettingsRequest(BigDecimal.ONE, null, null));

        assertThat(service.get(fixture.branch(), fixture.product())).isPresent();
        assertThat(service.list(fixture.branch(), fixture.product(), PageRequest.of(0, 1)).items())
                .hasSize(1);
        assertThat(service.list(fixture.branch(), fixture.product(), PageRequest.of(0, 1)).totalItems())
                .isOne();

        Fixture otherTenant = fixture(true);
        useActor(fixture);
        assertThatThrownBy(() -> service.get(fixture.branch(), otherTenant.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
        assertThatThrownBy(() -> service.get(otherTenant.branch(), fixture.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_NOT_FOUND"));
    }

    @Test
    void validatesPrecisionScaleSignAndBaseUnitIntegerSemantics() {
        Fixture integerFixture = fixture(false);
        for (BigDecimal invalid : new BigDecimal[] {
            new BigDecimal("-1"), new BigDecimal("0.001"), new BigDecimal("1000000000")
        }) {
            assertThatThrownBy(() -> service.upsert(
                            integerFixture.branch(),
                            integerFixture.product(),
                            new UpdateInventorySettingsRequest(invalid, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode())
                                    .isEqualTo("INVENTORY_SETTINGS_INVALID_QUANTITY"));
        }
        assertThatThrownBy(() -> service.upsert(
                        integerFixture.branch(),
                        integerFixture.product(),
                        new UpdateInventorySettingsRequest(BigDecimal.ONE, new BigDecimal("-1"), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("INVENTORY_SETTINGS_INVALID_QUANTITY"));

        Fixture decimalFixture = fixture(true);
        ProductInventorySettingsResponse response = service.upsert(
                decimalFixture.branch(),
                decimalFixture.product(),
                new UpdateInventorySettingsRequest(
                        new BigDecimal("0.125"), new BigDecimal("0.250"), null));
        assertThat(response.minStock()).isEqualByComparingTo("0.125");
    }

    @Test
    void onlyPhysicalTrackedProductsAreEligibleWithoutPublishedRequirement() {
        for (String mutation : new String[] {
            "UPDATE products SET tracking_stock = false WHERE id = ?",
            "UPDATE products SET product_type = 'service' WHERE id = ?",
            "UPDATE products SET product_type = 'kit' WHERE id = ?"
        }) {
            Fixture fixture = fixture(true);
            jdbc.update(mutation, fixture.product());
            assertThatThrownBy(() -> service.upsert(
                            fixture.branch(),
                            fixture.product(),
                            new UpdateInventorySettingsRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode())
                                    .isEqualTo("INVENTORY_SETTINGS_PRODUCT_NOT_ELIGIBLE"));
        }

        Fixture archived = fixture(true);
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", archived.product());
        assertThat(service.upsert(
                                archived.branch(),
                                archived.product(),
                                new UpdateInventorySettingsRequest(BigDecimal.ONE, null, null))
                        .productId())
                .isEqualTo(archived.product());
    }

    @Test
    void defaultLocationMustBelongToTenantAndBranchAndBeActive() {
        Fixture fixture = fixture(true);
        Fixture otherTenant = fixture(true);
        useActor(fixture);
        assertLocationError(fixture, otherTenant.location(), "INVENTORY_SETTINGS_LOCATION_NOT_FOUND");
        assertLocationError(fixture, fixture.otherLocation(), "INVENTORY_SETTINGS_LOCATION_BRANCH_MISMATCH");

        for (String status : new String[] {"inactive", "archived"}) {
            UUID location = addLocation(fixture.tenant(), fixture.branch(), status);
            assertLocationError(fixture, location, "INVENTORY_SETTINGS_LOCATION_NOT_ACTIVE");
        }

        ProductInventorySettingsResponse response = service.upsert(
                fixture.branch(),
                fixture.product(),
                new UpdateInventorySettingsRequest(BigDecimal.ONE, null, fixture.location()));
        assertThat(response.defaultLocationId()).isEqualTo(fixture.location());
        jdbc.update("UPDATE locations SET status = 'archived' WHERE id = ?", fixture.location());
        assertThat(service.get(fixture.branch(), fixture.product()))
                .get()
                .extracting(ProductInventorySettingsResponse::defaultLocationId)
                .isEqualTo(fixture.location());
    }

    @Test
    void branchAccessAndInventoryCapabilityAreRequired() {
        Fixture fixture = fixture(true);
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.get(fixture.branch(), fixture.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_ACCESS_DENIED"));

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.pos)));
        assertThatThrownBy(() -> service.get(fixture.branch(), fixture.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAPABILITY_REQUIRED"));
    }

    private void assertLocationError(Fixture fixture, UUID locationId, String code) {
        assertThatThrownBy(() -> service.upsert(
                        fixture.branch(),
                        fixture.product(),
                        new UpdateInventorySettingsRequest(BigDecimal.ONE, null, locationId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private Fixture fixture(boolean allowsDecimals) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID otherBranch = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Settings test', ?)", tenant, "set-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active'),
                       (?, ?, ?, 'Otra', 'store', 'active')
                """, branch, tenant, "A-" + branch.toString().substring(0, 8),
                otherBranch, tenant, "B-" + otherBranch.toString().substring(0, 8));
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + category);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', ?, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8), allowsDecimals);
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     status, tracking_stock)
                VALUES (?, ?, ?, 'Producto', 'physical', ?, ?, 'published', true)
                """, product, tenant, "SKU-" + product, category, unit);
        UUID location = addLocation(tenant, branch, "active");
        UUID otherLocation = addLocation(tenant, otherBranch, "active");
        Fixture fixture = new Fixture(
                tenant, branch, otherBranch, unit, product, location, otherLocation, UUID.randomUUID());
        useActor(fixture);
        return fixture;
    }

    private void useActor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee,
                UUID.randomUUID(), fixture.branch(), UUID.randomUUID()));
    }

    private UUID addLocation(UUID tenant, UUID branch, String status) {
        UUID location = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Ubicación', 'warehouse', ?)
                """, location, tenant, branch, "L-" + location.toString().substring(0, 8), status);
        return location;
    }

    private void insertBalance(Fixture fixture, String quantity, String reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances
                    (tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?::numeric, ?::numeric)
                """, fixture.tenant(), fixture.branch(), fixture.product(), quantity, reserved);
    }

    private long settingsCount(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM product_inventory_settings
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                """, Long.class, fixture.tenant(), fixture.branch(), fixture.product());
    }

    private BigDecimal balanceQuantity(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT quantity FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                """, BigDecimal.class, fixture.tenant(), fixture.branch(), fixture.product());
    }

    private long movementCount(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?", Long.class, tenant);
    }

    private record Fixture(
            UUID tenant,
            UUID branch,
            UUID otherBranch,
            UUID unit,
            UUID product,
            UUID location,
            UUID otherLocation,
            UUID user) {}
}
