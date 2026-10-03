package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
class InventoryExpirationQueryServiceTest {

    @Autowired private InventoryTraceabilityQueryService service;
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
    void returnsOnlyPhysicalExpiringStockWithInclusiveBoundariesAndDeterministicPaging() {
        Fixture fixture = fixture("America/Guatemala");
        LocalDate today = LocalDate.now(ZoneId.of("America/Guatemala"));
        UUID zeta = addProduct(fixture, "Zeta perecedero", true);
        UUID alpha = addProduct(fixture, "Alpha perecedero", true);
        UUID disabled = addProduct(fixture, "Sin tracking expiration", false);

        addLotBalance(fixture, zeta, "LOT-TODAY", today, fixture.locationA(), "1", "0");
        addLotBalance(fixture, zeta, "LOT-30", today.plusDays(30), fixture.locationA(), "2", "2");
        UUID sharedLot = addLot(fixture, zeta, "LOT-SHARED", today.plusDays(5));
        addLotBalance(fixture, sharedLot, fixture.locationA(), "1", "0");
        addLotBalance(fixture, sharedLot, fixture.locationB(), "3", "1");
        addLotBalance(fixture, zeta, "LOT-NULL-LOCATION", today.plusDays(7), null, "4", "0");
        addLotBalance(fixture, zeta, "LOT-31", today.plusDays(31), fixture.locationA(), "1", "0");
        addLotBalance(fixture, zeta, "LOT-YESTERDAY", today.minusDays(1), fixture.locationA(), "1", "0");
        addLotBalance(fixture, zeta, "LOT-NO-DATE", null, fixture.locationA(), "1", "0");
        addLotBalance(fixture, zeta, "LOT-ZERO", today.plusDays(8), fixture.locationA(), "0", "0");
        addLotBalance(fixture, alpha, "LOT-ALPHA", today, fixture.locationA(), "1", "0");
        addLotBalance(fixture, disabled, "LOT-DISABLED", today.plusDays(2), fixture.locationA(), "1", "0");

        Fixture foreign = fixture("America/Guatemala");
        UUID foreignProduct = addProduct(foreign, "Foreign", true);
        addLotBalance(foreign, foreignProduct, "LOT-FOREIGN", today, foreign.locationA(), "1", "0");
        useActor(fixture);

        var page = service.expiringLots(
                fixture.branch(), 30, null, null, PageRequest.of(0, 2));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.totalItems()).isEqualTo(6);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().productName()).isEqualTo("Alpha perecedero");
        assertThat(page.items().getFirst().daysUntilExpiration()).isZero();
        assertThat(page.items())
                .allSatisfy(item -> {
                    assertThat(item.expirationDate()).isBetween(today, today.plusDays(30));
                    assertThat(item.quantity()).isPositive();
                    assertThat(item.availableQuantity())
                            .isEqualByComparingTo(item.quantity().subtract(item.reservedQuantity()));
                });

        var all = service.expiringLots(
                fixture.branch(), 30, null, null, PageRequest.of(0, 500));
        assertThat(all.pageSize()).isEqualTo(100);
        assertThat(all.items()).extracting(item -> item.lotNumber())
                .containsExactlyInAnyOrder(
                        "LOT-ALPHA", "LOT-TODAY", "LOT-SHARED", "LOT-SHARED",
                        "LOT-NULL-LOCATION", "LOT-30")
                .doesNotContain(
                        "LOT-31", "LOT-YESTERDAY", "LOT-NO-DATE", "LOT-ZERO",
                        "LOT-DISABLED", "LOT-FOREIGN");
        assertThat(all.items())
                .filteredOn(item -> item.lotNumber().equals("LOT-30"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.daysUntilExpiration()).isEqualTo(30);
                    assertThat(item.availableQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
                });
        assertThat(all.items())
                .filteredOn(item -> item.lotNumber().equals("LOT-SHARED"))
                .extracting(item -> item.locationId())
                .containsExactlyInAnyOrder(fixture.locationA(), fixture.locationB());
        assertThat(all.items())
                .filteredOn(item -> item.lotNumber().equals("LOT-NULL-LOCATION"))
                .singleElement()
                .extracting(item -> item.locationId())
                .isNull();
    }

    @Test
    void appliesProductAndLocationFiltersWithoutLosingTenantOrBranchScope() {
        Fixture fixture = fixture("Pacific/Kiritimati");
        LocalDate businessDate = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));
        UUID selected = addProduct(fixture, "Seleccionado", true);
        UUID other = addProduct(fixture, "Otro", true);
        addLotBalance(fixture, selected, "SELECTED-A", businessDate, fixture.locationA(), "1", "0");
        addLotBalance(fixture, selected, "SELECTED-B", businessDate.plusDays(1), fixture.locationB(), "1", "0");
        addLotBalance(fixture, other, "OTHER", businessDate, fixture.locationA(), "1", "0");

        var result = service.expiringLots(
                fixture.branch(), 30, selected, fixture.locationA(), PageRequest.of(0, 20));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(selected);
            assertThat(item.locationId()).isEqualTo(fixture.locationA());
            assertThat(item.daysUntilExpiration()).isZero();
        });
    }

    @Test
    void invalidTenantTimezoneFallsBackToAmericaGuatemala() {
        Fixture fixture = fixture("Invalid/Timezone");
        LocalDate guatemalaDate = LocalDate.now(ZoneId.of("America/Guatemala"));
        UUID product = addProduct(fixture, "Fallback timezone", true);
        addLotBalance(
                fixture, product, "FALLBACK-TODAY", guatemalaDate,
                fixture.locationA(), "1", "0");

        var result = service.expiringLots(
                fixture.branch(), 1, null, null, PageRequest.of(0, 20));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.expirationDate()).isEqualTo(guatemalaDate);
            assertThat(item.daysUntilExpiration()).isZero();
        });
    }

    @Test
    void rejectsInvalidDaysAndDeniedBranchAccess() {
        Fixture fixture = fixture("America/Guatemala");

        assertCode(
                () -> service.expiringLots(
                        fixture.branch(), 0, null, null, PageRequest.of(0, 20)),
                "EXPIRATION_DAYS_INVALID");
        assertCode(
                () -> service.expiringLots(
                        fixture.branch(), 366, null, null, PageRequest.of(0, 20)),
                "EXPIRATION_DAYS_INVALID");

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertCode(
                () -> service.expiringLots(
                        fixture.branch(), 30, null, null, PageRequest.of(0, 20)),
                "BRANCH_ACCESS_DENIED");
    }

    private Fixture fixture(String timezone) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, timezone) VALUES (?, 'Expiration test', ?, ?)",
                tenant, "expiration-" + tenant, timezone);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "B-" + branch.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Caducidad', ?, 'active')
                """, category, tenant, "expiration-category-" + category);
        UUID locationA = addLocation(tenant, branch, "A");
        UUID locationB = addLocation(tenant, branch, "B");
        Fixture fixture = new Fixture(tenant, branch, unit, category, user, locationA, locationB);
        useActor(fixture);
        return fixture;
    }

    private UUID addLocation(UUID tenant, UUID branch, String code) {
        UUID location = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, 'warehouse', 'active')
                """, location, tenant, branch, code + "-" + location, "Location " + code);
        return location;
    }

    private UUID addProduct(Fixture fixture, String name, boolean trackingExpiration) {
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id,
                     status, tracking_stock, tracking_lot, tracking_expiration)
                VALUES (?, ?, ?, ?, 'physical', ?, ?, 'published', true, true, ?)
                """, product, fixture.tenant(), "SKU-" + product, name,
                fixture.category(), fixture.unit(), trackingExpiration);
        return product;
    }

    private UUID addLot(
            Fixture fixture, UUID productId, String lotNumber, LocalDate expirationDate) {
        UUID lot = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_lots
                    (id, tenant_id, product_id, lot_number, expiration_date)
                VALUES (?, ?, ?, ?, ?)
                """, lot, fixture.tenant(), productId, lotNumber, expirationDate);
        return lot;
    }

    private void addLotBalance(
            Fixture fixture,
            UUID productId,
            String lotNumber,
            LocalDate expirationDate,
            UUID locationId,
            String quantity,
            String reservedQuantity) {
        addLotBalance(
                fixture, addLot(fixture, productId, lotNumber, expirationDate), locationId,
                quantity, reservedQuantity);
    }

    private void addLotBalance(
            Fixture fixture,
            UUID lotId,
            UUID locationId,
            String quantity,
            String reservedQuantity) {
        jdbc.update("""
                INSERT INTO inventory_lot_balances
                    (id, tenant_id, branch_id, location_id, lot_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)
                """, UUID.randomUUID(), fixture.tenant(), fixture.branch(), locationId, lotId,
                quantity, reservedQuantity);
    }

    private void useActor(Fixture fixture) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                fixture.user(), fixture.tenant(), UserType.employee,
                UUID.randomUUID(), fixture.branch(), UUID.randomUUID()));
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private record Fixture(
            UUID tenant,
            UUID branch,
            UUID unit,
            UUID category,
            UUID user,
            UUID locationA,
            UUID locationB) {}
}
