package com.omniretail.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentRequest;
import com.omniretail.backend.inventory.dto.InventoryAdjustmentType;
import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.dto.InventoryKitAvailabilityComponentDto;
import com.omniretail.backend.inventory.dto.InventoryKitAvailabilityResponse;
import com.omniretail.backend.inventory.dto.InventoryProductMode;
import com.omniretail.backend.inventory.dto.InventoryStockDisplayStatus;
import com.omniretail.backend.inventory.dto.InventoryStockItemDto;
import com.omniretail.backend.shared.exception.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** GET /inventory/stock con productos physical, service y kit mezclados. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class InventoryStockMixedProductsTest {

    private static final List<ProductType> ALL_TYPES =
            List.of(ProductType.physical, ProductType.service, ProductType.kit);

    @Autowired private InventoryStockQueryService service;
    @Autowired private InventoryTraceabilityAdjustmentService adjustmentService;
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
    void withoutProductTypesTheListIsExactlyThePhysicalOne() {
        Fixture f = fixture();
        UUID physical = physical(f, "Fisico", "10", "0", "5");
        service(f, "Servicio");
        kit(f, "Kit", List.of(component(physical, "1")));

        var response = service.list(f.branch(), null, null, null, PageRequest.of(0, 20));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(physical);
            assertThat(item.productType()).isEqualTo(ProductType.physical);
            assertThat(item.inventoryMode()).isEqualTo(InventoryProductMode.TRACKED);
            assertThat(item.displayStatus()).isEqualTo(InventoryStockDisplayStatus.NORMAL);
            assertThat(item.status()).isEqualTo(InventoryAlertStatus.normal);
            assertThat(item.quantity()).isEqualByComparingTo("10");
        });
        assertThat(response.totalItems()).isEqualTo(1);
    }

    @Test
    void allThreeTypesAreReturnedAndServiceRowIsInformativeOnly() {
        Fixture f = fixture();
        physical(f, "A fisico", "10", "0", "5");
        service(f, "B servicio");
        UUID component = physical(f, "C componente", "6", "0", null);
        kit(f, "D kit", List.of(component(component, "2")));

        var response = list(f, null, null, null, PageRequest.of(0, 20));

        assertThat(response.items()).extracting(InventoryStockItemDto::productType)
                .containsExactly(ProductType.physical, ProductType.service, ProductType.physical, ProductType.kit);
        InventoryStockItemDto service = byName(response.items(), "B servicio");
        assertThat(service.inventoryMode()).isEqualTo(InventoryProductMode.NONE);
        assertThat(service.displayStatus()).isEqualTo(InventoryStockDisplayStatus.NOT_CONTROLLED);
        assertThat(service.status()).isNull();
        assertThat(service.quantity()).isNull();
        assertThat(service.reservedQuantity()).isNull();
        assertThat(service.availableQuantity()).isNull();
        assertThat(service.minStock()).isNull();
        assertThat(service.reorderPoint()).isNull();
        assertThat(service.defaultLocationId()).isNull();
        assertThat(service.defaultLocationName()).isNull();
        assertThat(service.nextExpirationDate()).isNull();
        assertThat(service.suggestedReorder()).isNull();
        assertThat(count("inventory_balances", f, service.productId())).isZero();
    }

    @Test
    void serviceAndKitNeverEnterKpisOrPhysicalAlertStatuses() {
        Fixture f = fixture();
        physical(f, "Sin stock", null, null, null);
        physical(f, "Critico", "5", "0", "10");
        physical(f, "Normal", "20", "0", "10");
        UUID component = physical(f, "Componente", "0", "0", null);
        service(f, "Servicio");
        kit(f, "Kit sin stock", List.of(component(component, "1")));

        var physicalOnly = service.list(f.branch(), null, null, null, PageRequest.of(0, 20));
        var mixed = list(f, null, null, null, PageRequest.of(0, 50));

        assertThat(mixed.summary()).isEqualTo(physicalOnly.summary());
        assertThat(mixed.summary().activeProducts()).isEqualTo(4);
        assertThat(mixed.summary().outOfStock()).isEqualTo(2);
        assertThat(mixed.totalItems()).isEqualTo(6);
        // El filtro de estado físico excluye servicio y kit y no les inventa un estado de alerta.
        var critical = list(f, null, null, InventoryAlertStatus.critical, PageRequest.of(0, 20));
        assertThat(critical.items()).extracting(InventoryStockItemDto::productType)
                .containsOnly(ProductType.physical);
        var outOfStock = list(f, null, null, InventoryAlertStatus.out_of_stock, PageRequest.of(0, 20));
        assertThat(outOfStock.items()).extracting(InventoryStockItemDto::productType)
                .containsOnly(ProductType.physical);
        assertThat(outOfStock.totalItems()).isEqualTo(2);
    }

    @Test
    void kitAvailabilityIsTheMinimumOfWholeKitsPerComponent() {
        Fixture f = fixture();
        UUID a = physical(f, "Componente A", "12", "1", null); // disponible 11, requiere 2 => 5
        UUID b = physical(f, "Componente B", "8", "0", null); // disponible 8, requiere 1 => 8
        UUID kit = kit(f, "Kit combo", List.of(component(a, "2"), component(b, "1")));

        InventoryStockItemDto item = byId(list(f, null, null, null, PageRequest.of(0, 20)).items(), kit);

        assertThat(item.productType()).isEqualTo(ProductType.kit);
        assertThat(item.inventoryMode()).isEqualTo(InventoryProductMode.DERIVED_KIT);
        assertThat(item.availableQuantity()).isEqualByComparingTo("5");
        assertThat(item.displayStatus()).isEqualTo(InventoryStockDisplayStatus.KIT_AVAILABLE);
        assertThat(item.status()).isNull();
        assertThat(item.quantity()).isNull();
        assertThat(item.reservedQuantity()).isNull();
        assertThat(item.minStock()).isNull();
        assertThat(item.defaultLocationId()).isNull();
        assertThat(item.nextExpirationDate()).isNull();
        assertThat(item.suggestedReorder()).isNull();
        assertThat(count("inventory_balances", f, kit)).isZero();
    }

    @Test
    void kitWithMissingBalanceNoComponentsOrInvalidComponentIsUnavailable() {
        Fixture f = fixture();
        UUID withStock = physical(f, "Con stock", "10", "0", null);
        UUID withoutBalance = physical(f, "Sin balance", null, null, null);
        UUID kitMissingBalance = kit(f, "Kit sin balance", List.of(component(withStock, "1"), component(withoutBalance, "1")));
        UUID kitNoComponents = kit(f, "Kit vacio", List.of());
        UUID archived = physical(f, "Archivado", "10", "0", null);
        UUID kitInvalid = kit(f, "Kit componente invalido", List.of(component(withStock, "1"), component(archived, "1")));
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", archived);
        UUID untracked = physical(f, "Sin control", "10", "0", null);
        UUID kitUntracked = kit(f, "Kit sin control", List.of(component(untracked, "1")));
        jdbc.update("UPDATE products SET tracking_stock = false WHERE id = ?", untracked);

        var items = list(f, null, null, null, PageRequest.of(0, 50)).items();

        for (UUID kit : List.of(kitMissingBalance, kitNoComponents, kitInvalid, kitUntracked)) {
            InventoryStockItemDto item = byId(items, kit);
            assertThat(item.availableQuantity()).isEqualByComparingTo("0");
            assertThat(item.displayStatus()).isEqualTo(InventoryStockDisplayStatus.KIT_UNAVAILABLE);
        }
    }

    @Test
    void kitAvailabilityUsesOnlyTheRequestedBranchAndTenantBalances() {
        Fixture f = fixture();
        UUID component = physical(f, "Componente", "9", "0", null);
        UUID kit = kit(f, "Kit", List.of(component(component, "3")));
        UUID otherBranch = addBranch(f);
        jdbc.update("""
                INSERT INTO inventory_balances (tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, 90, 0)
                """, f.tenant(), otherBranch, component);
        Fixture otherTenant = fixture();
        physical(otherTenant, "Ajeno", "5", "0", null);
        use(f);

        assertThat(byId(list(f, null, null, null, PageRequest.of(0, 20)).items(), kit).availableQuantity())
                .isEqualByComparingTo("3");
        assertThat(list(f, null, null, null, PageRequest.of(0, 50)).items())
                .extracting(InventoryStockItemDto::productName)
                .doesNotContain("Ajeno");
        // En otra sucursal el kit se calcula con SUS balances: ahi el componente tiene 90 => 30 kits.
        assertThat(byId(service.list(otherBranch, null, null, null, ALL_TYPES, PageRequest.of(0, 20)).items(), kit)
                        .availableQuantity())
                .isEqualByComparingTo("30");
    }

    @Test
    void mixedPaginationSearchAndCategoryApplyToTheWholeSet() {
        Fixture f = fixture();
        UUID other = addCategory(f, "Otra categoria");
        UUID component = physical(f, "Aaa fisico", "10", "0", null);
        service(f, "Bbb servicio");
        kit(f, "Ccc kit", List.of(component(component, "1")));
        UUID inOther = serviceIn(f, other, "Ddd servicio otra");

        var first = list(f, null, null, null, PageRequest.of(0, 2));
        var second = list(f, null, null, null, PageRequest.of(1, 2));
        assertThat(first.totalItems()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).extracting(InventoryStockItemDto::productName)
                .containsExactly("Aaa fisico", "Bbb servicio");
        assertThat(second.items()).extracting(InventoryStockItemDto::productName)
                .containsExactly("Ccc kit", "Ddd servicio otra");

        assertThat(list(f, "KIT", null, null, PageRequest.of(0, 20)).items())
                .extracting(InventoryStockItemDto::productName).containsExactly("Ccc kit");
        var byCategory = list(f, null, other, null, PageRequest.of(0, 20));
        assertThat(byCategory.items()).extracting(InventoryStockItemDto::productId).containsExactly(inOther);
        assertThat(byCategory.totalItems()).isEqualTo(1);
        assertThat(byCategory.summary().activeProducts()).isZero();
    }

    @Test
    void sortByAvailableQuantityPutsRowsWithoutQuantityLastInBothDirectionsAndSkuSortWorks() {
        Fixture f = fixture();
        physical(f, "Poco", "2", "0", null);
        physical(f, "Mucho", "30", "0", null);
        service(f, "Servicio");

        for (Sort.Direction direction : Sort.Direction.values()) {
            var items = service.list(
                    f.branch(), null, null, null, ALL_TYPES,
                    PageRequest.of(0, 20, Sort.by(direction, "availableQuantity"))).items();
            assertThat(items.getLast().productName()).isEqualTo("Servicio");
        }
        var asc = service.list(f.branch(), null, null, null, ALL_TYPES,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "availableQuantity"))).items();
        assertThat(asc).extracting(InventoryStockItemDto::productName).containsExactly("Poco", "Mucho", "Servicio");
        var status = service.list(f.branch(), null, null, null, ALL_TYPES,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "status"))).items();
        assertThat(status.getLast().productType()).isEqualTo(ProductType.service);
        assertThat(service.list(f.branch(), null, null, null, ALL_TYPES,
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "sku"))).items())
                .hasSize(3);
    }

    @Test
    void tenantIsolationPermissionsAndCapabilityAreEnforced() {
        Fixture a = fixture();
        UUID serviceA = service(a, "Servicio A");
        Fixture b = fixture();
        service(b, "Servicio B");
        use(a);

        assertThat(service.list(a.branch(), null, null, null, ALL_TYPES, PageRequest.of(0, 20)).items())
                .extracting(InventoryStockItemDto::productId).containsExactly(serviceA);

        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.pos)));
        assertThatThrownBy(() -> service.list(a.branch(), null, null, null, ALL_TYPES, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAPABILITY_REQUIRED"));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.list(a.branch(), null, null, null, ALL_TYPES, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void serviceAndKitStillCannotBeAdjustedDirectly() {
        Fixture f = fixture();
        UUID service = service(f, "Servicio");
        UUID component = physical(f, "Componente", "5", "0", null);
        UUID kit = kit(f, "Kit", List.of(component(component, "1")));

        for (UUID product : List.of(service, kit)) {
            for (InventoryAdjustmentType type : InventoryAdjustmentType.values()) {
                assertThatThrownBy(() -> adjustmentService.adjust(new InventoryAdjustmentRequest(
                                f.branch(), product, type, BigDecimal.ONE, "Ajuste", null, null,
                                null, null, null, null, null)))
                        .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode())
                                .isEqualTo("INVENTORY_ADJUSTMENT_PRODUCT_UNSUPPORTED"));
            }
        }
        assertThat(count("inventory_balances", f, service)).isZero();
        assertThat(count("inventory_balances", f, kit)).isZero();
    }

    @Test
    void crossBranchStockRejectsServiceAndKitInsteadOfReturningMisleadingZeros() {
        Fixture f = fixture();
        UUID service = service(f, "Servicio");
        UUID component = physical(f, "Componente", "5", "0", null);
        UUID kit = kit(f, "Kit", List.of(component(component, "1")));
        addBranch(f);

        for (UUID product : List.of(service, kit)) {
            assertThatThrownBy(() -> this.service.listBranches(product, f.branch()))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode())
                            .isEqualTo("INVENTORY_STOCK_BRANCHES_PRODUCT_UNSUPPORTED"));
        }
        assertThat(this.service.listBranches(component, f.branch())).hasSize(1);
        assertThatThrownBy(() -> this.service.listBranches(UUID.randomUUID(), f.branch()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    // --------------------------------------------------- kit availability detail

    @Test
    void kitAvailabilityExplainsTheBottleneckAndReservedStockReducesIt() {
        Fixture f = fixture();
        UUID a = physical(f, "Componente A", "12", "1", null); // disponible 11 (12 - 1 reservado), 2 por kit => 5
        UUID b = physical(f, "Componente B", "8", "0", null); // disponible 8, 1 por kit => 8
        UUID kit = kit(f, "Kit", List.of(component(a, "2"), component(b, "1")));

        InventoryKitAvailabilityResponse response = service.kitAvailability(kit, f.branch());

        assertThat(response.kitProductId()).isEqualTo(kit);
        assertThat(response.branchId()).isEqualTo(f.branch());
        assertThat(response.availableKits()).isEqualTo(5);
        assertThat(response.components()).extracting(InventoryKitAvailabilityComponentDto::componentProductId)
                .containsExactly(a, b);
        InventoryKitAvailabilityComponentDto first = response.components().getFirst();
        assertThat(first.sku()).startsWith("SKU-");
        assertThat(first.productName()).isEqualTo("Componente A");
        assertThat(first.quantityPerKit()).isEqualByComparingTo("2");
        assertThat(first.availableQuantity()).isEqualByComparingTo("11");
        assertThat(first.kitCapacity()).isEqualTo(5);
        assertThat(first.limiting()).isTrue();
        assertThat(response.components().get(1).kitCapacity()).isEqualTo(8);
        assertThat(response.components().get(1).limiting()).isFalse();
    }

    @Test
    void tiedComponentsAreAllLimiting() {
        Fixture f = fixture();
        UUID a = physical(f, "A", "10", "0", null); // 10 / 2 = 5
        UUID b = physical(f, "B", "5", "0", null); // 5 / 1 = 5
        UUID c = physical(f, "C", "20", "0", null); // 20 / 1 = 20
        UUID kit = kit(f, "Kit", List.of(component(a, "2"), component(b, "1"), component(c, "1")));

        InventoryKitAvailabilityResponse response = service.kitAvailability(kit, f.branch());

        assertThat(response.availableKits()).isEqualTo(5);
        assertThat(response.components()).extracting(InventoryKitAvailabilityComponentDto::limiting)
                .containsExactly(true, true, false);
    }

    @Test
    void componentWithoutBalanceIsZeroAndLimitingAndTheKitIsUnavailable() {
        Fixture f = fixture();
        UUID stocked = physical(f, "Con stock", "10", "0", null);
        UUID empty = physical(f, "Sin balance", null, null, null);
        UUID kit = kit(f, "Kit", List.of(component(stocked, "1"), component(empty, "1")));

        InventoryKitAvailabilityResponse response = service.kitAvailability(kit, f.branch());

        assertThat(response.availableKits()).isZero();
        InventoryKitAvailabilityComponentDto limiting = response.components().getFirst();
        assertThat(limiting.componentProductId()).isEqualTo(empty);
        assertThat(limiting.availableQuantity()).isEqualByComparingTo("0");
        assertThat(limiting.kitCapacity()).isZero();
        assertThat(limiting.limiting()).isTrue();
        assertThat(response.components().get(1).limiting()).isFalse();
    }

    @Test
    void archivedOrUntrackedComponentsHaveZeroEffectiveAvailabilityWithoutError() {
        Fixture f = fixture();
        UUID archived = physical(f, "Archivado", "10", "0", null);
        UUID untracked = physical(f, "Sin control", "10", "0", null);
        UUID healthy = physical(f, "Sano", "10", "0", null);
        UUID kit = kit(f, "Kit", List.of(component(archived, "1"), component(untracked, "1"), component(healthy, "1")));
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", archived);
        jdbc.update("UPDATE products SET tracking_stock = false WHERE id = ?", untracked);

        InventoryKitAvailabilityResponse response = service.kitAvailability(kit, f.branch());

        assertThat(response.availableKits()).isZero();
        assertThat(response.components()).filteredOn(c -> !c.componentProductId().equals(healthy))
                .allSatisfy(c -> {
                    assertThat(c.availableQuantity()).isEqualByComparingTo("0");
                    assertThat(c.kitCapacity()).isZero();
                    assertThat(c.limiting()).isTrue();
                });
        assertThat(response.components()).filteredOn(c -> c.componentProductId().equals(healthy))
                .singleElement().satisfies(c -> assertThat(c.limiting()).isFalse());
    }

    @Test
    void kitWithoutComponentsHasZeroAvailabilityAndNoComponents() {
        Fixture f = fixture();
        UUID kit = kit(f, "Kit vacio", List.of());

        InventoryKitAvailabilityResponse response = service.kitAvailability(kit, f.branch());

        assertThat(response.availableKits()).isZero();
        assertThat(response.components()).isEmpty();
    }

    @Test
    void componentsAreOrderedByCapacityThenNameThenId() {
        Fixture f = fixture();
        UUID zeta = physical(f, "Zeta", "5", "0", null);
        UUID beta = physical(f, "Beta", "5", "0", null);
        UUID alfa = physical(f, "Alfa", "9", "0", null);
        UUID kit = kit(f, "Kit", List.of(component(zeta, "1"), component(alfa, "1"), component(beta, "1")));

        assertThat(service.kitAvailability(kit, f.branch()).components())
                .extracting(InventoryKitAvailabilityComponentDto::productName)
                .containsExactly("Beta", "Zeta", "Alfa");
    }

    @Test
    void kitAvailabilityMatchesTheKitAvailableQuantityOfTheStockList() {
        Fixture f = fixture();
        UUID a = physical(f, "A", "12", "1", null);
        UUID b = physical(f, "B", "8", "0", null);
        UUID c = physical(f, "C", null, null, null);
        UUID healthy = kit(f, "Kit sano", List.of(component(a, "2"), component(b, "1")));
        UUID blocked = kit(f, "Kit bloqueado", List.of(component(a, "1"), component(c, "1")));
        UUID empty = kit(f, "Kit vacio", List.of());

        var items = list(f, null, null, null, PageRequest.of(0, 50)).items();

        for (UUID kit : List.of(healthy, blocked, empty)) {
            assertThat(byId(items, kit).availableQuantity())
                    .isEqualByComparingTo(BigDecimal.valueOf(service.kitAvailability(kit, f.branch()).availableKits()));
        }
        assertThat(service.kitAvailability(healthy, f.branch()).availableKits()).isEqualTo(5);
    }

    @Test
    void kitAvailabilityIsScopedToTheRequestedBranchAndTenant() {
        Fixture f = fixture();
        UUID component = physical(f, "Componente", "9", "0", null);
        UUID kit = kit(f, "Kit", List.of(component(component, "3")));
        UUID otherBranch = addBranch(f);
        jdbc.update("""
                INSERT INTO inventory_balances (tenant_id, branch_id, product_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, 90, 0)
                """, f.tenant(), otherBranch, component);
        Fixture otherTenant = fixture();
        UUID foreignKit = kit(otherTenant, "Kit ajeno", List.of());
        use(f);

        assertThat(service.kitAvailability(kit, f.branch()).availableKits()).isEqualTo(3);
        assertThat(service.kitAvailability(kit, otherBranch).availableKits()).isEqualTo(30);
        assertThatThrownBy(() -> service.kitAvailability(foreignKit, f.branch()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
        assertThatThrownBy(() -> service.kitAvailability(kit, otherTenant.branch()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_NOT_FOUND"));
    }

    @Test
    void kitAvailabilityEnforcesBranchAccessAndInventoryCapability() {
        Fixture f = fixture();
        UUID kit = kit(f, "Kit", List.of());

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> service.kitAvailability(kit, f.branch()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("BRANCH_ACCESS_DENIED"));

        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.pos)));
        assertThatThrownBy(() -> service.kitAvailability(kit, f.branch()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAPABILITY_REQUIRED"));
    }

    @Test
    void kitAvailabilityRejectsPhysicalAndServiceProducts() {
        Fixture f = fixture();
        UUID physical = physical(f, "Fisico", "5", "0", null);
        UUID service = service(f, "Servicio");

        for (UUID product : List.of(physical, service)) {
            assertThatThrownBy(() -> this.service.kitAvailability(product, f.branch()))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo("INVENTORY_KIT_AVAILABILITY_PRODUCT_UNSUPPORTED");
                        assertThat(exception.getStatus().value()).isEqualTo(400);
                    });
        }
        assertThatThrownBy(() -> this.service.kitAvailability(UUID.randomUUID(), f.branch()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- helpers

    private com.omniretail.backend.inventory.dto.InventoryStockPageResponse list(
            Fixture f, String search, UUID category, InventoryAlertStatus status,
            org.springframework.data.domain.Pageable pageable) {
        return service.list(f.branch(), search, category, status, ALL_TYPES, pageable);
    }

    private static InventoryStockItemDto byName(List<InventoryStockItemDto> items, String name) {
        return items.stream().filter(item -> item.productName().equals(name)).findFirst().orElseThrow();
    }

    private static InventoryStockItemDto byId(List<InventoryStockItemDto> items, UUID id) {
        return items.stream().filter(item -> item.productId().equals(id)).findFirst().orElseThrow();
    }

    private record KitComponent(UUID product, String quantity) {}

    private static KitComponent component(UUID product, String quantityPerKit) {
        return new KitComponent(product, quantityPerKit);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Mixed stock', ?)", tenant, "mixed-" + tenant);
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, 'Principal', 'main', 'active')",
                branch, tenant, "B-" + branch.toString().substring(0, 8));
        jdbc.update("INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')",
                unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, 'Categoria stock', ?, 'active')",
                category, tenant, "cat-" + category);
        Fixture fixture = new Fixture(tenant, branch, unit, category, UUID.randomUUID());
        use(fixture);
        return fixture;
    }

    private void use(Fixture f) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                f.user(), f.tenant(), UserType.employee, UUID.randomUUID(), f.branch(), UUID.randomUUID()));
    }

    private UUID addBranch(Fixture f) {
        UUID branch = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, 'Secundaria', 'store', 'active')",
                branch, f.tenant(), "B-" + branch.toString().substring(0, 8));
        return branch;
    }

    private UUID addCategory(Fixture f, String name) {
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, ?, ?)",
                category, f.tenant(), name, "category-" + category);
        return category;
    }

    private UUID insertProduct(Fixture f, UUID category, String type, String name, boolean trackingStock) {
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id, status, tracking_stock)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'published', ?)
                """, product, f.tenant(), "SKU-" + product, name, type, category, f.unit(), trackingStock);
        return product;
    }

    private UUID physical(Fixture f, String name, String quantity, String reserved, String minStock) {
        UUID product = insertProduct(f, f.category(), "physical", name, true);
        if (minStock != null) {
            jdbc.update("""
                    INSERT INTO product_inventory_settings (tenant_id, branch_id, product_id, min_stock, reorder_point)
                    VALUES (?, ?, ?, ?::numeric, NULL)
                    """, f.tenant(), f.branch(), product, minStock);
        }
        if (quantity != null) {
            jdbc.update("""
                    INSERT INTO inventory_balances (tenant_id, branch_id, product_id, quantity, reserved_quantity)
                    VALUES (?, ?, ?, ?::numeric, ?::numeric)
                    """, f.tenant(), f.branch(), product, quantity, reserved);
        }
        return product;
    }

    private UUID service(Fixture f, String name) {
        return serviceIn(f, f.category(), name);
    }

    private UUID serviceIn(Fixture f, UUID category, String name) {
        return insertProduct(f, category, "service", name, false);
    }

    private UUID kit(Fixture f, String name, List<KitComponent> components) {
        UUID kit = insertProduct(f, f.category(), "kit", name, false);
        for (KitComponent component : components) {
            jdbc.update("""
                    INSERT INTO product_kit_components
                        (tenant_id, kit_product_id, component_product_id, quantity_per_kit)
                    VALUES (?, ?, ?, ?::numeric)
                    """, f.tenant(), kit, component.product(), component.quantity());
        }
        return kit;
    }

    private long count(String table, Fixture f, UUID product) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND product_id = ?",
                Long.class, f.tenant(), product);
    }

    private record Fixture(UUID tenant, UUID branch, UUID unit, UUID category, UUID user) {}
}
